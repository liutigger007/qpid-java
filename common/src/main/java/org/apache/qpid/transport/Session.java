/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.transport;


import org.apache.qpid.configuration.ClientProperties;
import org.apache.qpid.transport.network.Frame;
import org.apache.qpid.transport.util.Waiter;
import static org.apache.qpid.transport.Option.COMPLETED;
import static org.apache.qpid.transport.Option.SYNC;
import static org.apache.qpid.transport.Option.TIMELY_REPLY;
import static org.apache.qpid.transport.Session.State.CLOSED;
import static org.apache.qpid.transport.Session.State.CLOSING;
import static org.apache.qpid.transport.Session.State.DETACHED;
import static org.apache.qpid.transport.Session.State.NEW;
import static org.apache.qpid.transport.Session.State.OPEN;
import static org.apache.qpid.transport.Session.State.RESUMING;
import static org.apache.qpid.util.Serial.ge;
import static org.apache.qpid.util.Serial.gt;
import static org.apache.qpid.util.Serial.le;
import static org.apache.qpid.util.Serial.lt;
import static org.apache.qpid.util.Serial.max;
import static org.apache.qpid.util.Strings.toUTF8;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session
 *
 * @author Rafael H. Schloming
 */

public class Session extends SessionInvoker
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Session.class);

    public enum State { NEW, DETACHED, RESUMING, OPEN, CLOSING, CLOSED }

    static class DefaultSessionListener implements SessionListener
    {

        public void opened(Session ssn) {}

        public void resumed(Session ssn) {}

        public void message(Session ssn, MessageTransfer xfr)
        {
            LOGGER.info("message: {}", xfr);
        }

        public void exception(Session ssn, SessionException exc)
        {
            LOGGER.error("session exception", exc);
        }

        public void closed(Session ssn) {}
    }

    public static final int UNLIMITED_CREDIT = 0xFFFFFFFF;

    private Connection connection;
    private Binary name;
    private long expiry;
    private boolean closing;
    private int channel;
    private SessionDelegate delegate;
    private SessionListener listener = new DefaultSessionListener();
    private final long timeout = Long.getLong(ClientProperties.QPID_SYNC_OP_TIMEOUT,
                                        Long.getLong(LegacyClientProperties.AMQJ_DEFAULT_SYNCWRITE_TIMEOUT,
                                                     ClientProperties.DEFAULT_SYNC_OPERATION_TIMEOUT));
    private final long blockedSendTimeout = Long.getLong(ClientProperties.QPID_FLOW_CONTROL_WAIT_FAILURE,
                                                         ClientProperties.DEFAULT_FLOW_CONTROL_WAIT_FAILURE);
    private long blockedSendReportingPeriod = Long.getLong(ClientProperties.QPID_FLOW_CONTROL_WAIT_NOTIFY_PERIOD,
                                                           ClientProperties.DEFAULT_FLOW_CONTROL_WAIT_NOTIFY_PERIOD);

    private boolean autoSync = false;

    private boolean incomingInit;
    // incoming command count
    private int commandsIn;
    // completed incoming commands
    private final Object processedLock = new Object();
    private RangeSet processed;
    private int maxProcessed;
    private int syncPoint;

    // outgoing command count
    private int commandsOut = 0;
    private final int commandLimit = Integer.getInteger("qpid.session.command_limit", 64 * 1024);
    private Map<Integer,Method> commands = new HashMap<Integer, Method>();
    private final Object commandsLock = new Object();
    private int commandBytes = 0;
    private int byteLimit = Integer.getInteger("qpid.session.byte_limit", 1024*1024);
    private int maxComplete = commandsOut - 1;
    private boolean needSync = false;

    private State state = NEW;

    // transfer flow control
    private volatile boolean flowControl = false;
    private Semaphore credit = new Semaphore(0);

    private Thread resumer = null;
    private boolean transacted = false;
    private SessionDetachCode detachCode;
    private final Object stateLock = new Object();

    private final AtomicBoolean _failoverRequired = new AtomicBoolean(false);
    private boolean _isNoReplay = false;

    protected Session(Connection connection, Binary name, long expiry)
    {
        this(connection, new SessionDelegate(), name, expiry);
    }

    protected Session(Connection connection, Binary name, long expiry, boolean noReplay)
    {
        this(connection, new SessionDelegate(), name, expiry, noReplay);
    }

    protected Session(Connection connection, SessionDelegate delegate, Binary name, long expiry)
    {
        this(connection, delegate, name, expiry,false);
    }

    protected Session(Connection connection, SessionDelegate delegate, Binary name, long expiry, boolean noReplay)
    {
        this.connection = connection;
        this.delegate = delegate;
        this.name = name;
        this.expiry = expiry;
        this.closing = false;
        this._isNoReplay = noReplay;
        initReceiver();
    }

    public Connection getConnection()
    {
        return connection;
    }

    public Binary getName()
    {
        return name;
    }

    void setExpiry(long expiry)
    {
        this.expiry = expiry;
    }

    protected void setClose(boolean close)
    {
        this.closing = close;
    }

    public int getChannel()
    {
        return channel;
    }

    void setChannel(int channel)
    {
        this.channel = channel;
    }

    public void setSessionListener(SessionListener listener)
    {
        if (listener == null)
        {
            this.listener = new DefaultSessionListener();
        }
        else
        {
            this.listener = listener;
        }
    }

    public SessionListener getSessionListener()
    {
        return listener;
    }

    public void setAutoSync(boolean value)
    {
        synchronized (commandsLock)
        {
            this.autoSync = value;
        }
    }

    protected void setState(State state)
    {
        synchronized (commandsLock)
        {
            this.state = state;
            commandsLock.notifyAll();
        }
    }

    protected State getState()
    {
        return this.state;
    }

    void setFlowControl(boolean value)
    {
        flowControl = value;
    }

    void addCredit(int value)
    {
        credit.release(value);
    }

    void drainCredit()
    {
        credit.drainPermits();
    }

    void acquireCredit()
    {
        if (flowControl)
        {
            try
            {
                long wait = blockedSendTimeout > blockedSendReportingPeriod ? blockedSendReportingPeriod :
                           blockedSendTimeout;
                long totalWait = 1L;
                while(totalWait <= blockedSendTimeout && !credit.tryAcquire(wait, TimeUnit.MILLISECONDS))
                {
                    totalWait+=wait;
                    LOGGER.warn("Message send delayed by {}s due to broker enforced flow control", (totalWait) / 1000);


                }
                if(totalWait > blockedSendTimeout)
                {
                    LOGGER.error("Message send failed due to timeout waiting on broker enforced flow control");
                    throw new SessionException
                            ("timed out waiting for message credit");
                }
            }
            catch (InterruptedException e)
            {
                throw new SessionException
                    ("interrupted while waiting for credit", null, e);
            }
        }
    }

    private void initReceiver()
    {
        synchronized (processedLock)
        {
            incomingInit = false;
            processed = RangeSetFactory.createRangeSet();
        }
    }

    void attach()
    {
        initReceiver();
        sessionAttach(name.getBytes());
        sessionRequestTimeout(0);//use expiry here only if/when session resume is supported
    }

    void resume()
    {
        _failoverRequired.set(false);

        synchronized (commandsLock)
        {
            attach();

            for (int i = maxComplete + 1; lt(i, commandsOut); i++)
            {
                Method m = getCommand(i);
                if (m == null)
                {
                    m = new ExecutionSync();
                    m.setId(i);
                }
                else if (m instanceof MessageTransfer)
                {
                	MessageTransfer xfr = (MessageTransfer)m;

                    Header header = xfr.getHeader();

                    if (header != null)
                	{
                		if (header.getDeliveryProperties() != null)
                		{
                		   header.getDeliveryProperties().setRedelivered(true);
                		}
                		else
                		{
                			DeliveryProperties deliveryProps = new DeliveryProperties();
                    		deliveryProps.setRedelivered(true);

                    		xfr.setHeader(new Header(deliveryProps, header.getMessageProperties(),
                                                     header.getNonStandardProperties()));
                		}

                	}
                	else
                	{
                		DeliveryProperties deliveryProps = new DeliveryProperties();
                		deliveryProps.setRedelivered(true);
                		xfr.setHeader(new Header(deliveryProps, null, null));
                	}
                }
                sessionCommandPoint(m.getId(), 0);
                send(m);
            }

            sessionCommandPoint(commandsOut, 0);

            sessionFlush(COMPLETED);
            resumer = Thread.currentThread();
            state = RESUMING;

            if(isTransacted())
            {
                txSelect();
            }

            listener.resumed(this);
            resumer = null;
        }
    }

    private Method getCommand(int i)
    {
        return commands.get(i);
    }

    private void setCommand(int commandId, Method command)
    {
        commands.put(commandId, command);
    }

    private Method removeCommand(int id)
    {
        return commands.remove(id);
    }

    final void commandPoint(int id)
    {
        synchronized (processedLock)
        {
            this.commandsIn = id;
            if (!incomingInit)
            {
                incomingInit = true;
                maxProcessed = commandsIn - 1;
                syncPoint = maxProcessed;
            }
        }
    }

    public int getCommandsOut()
    {
        return commandsOut;
    }

    public int getCommandsIn()
    {
        return commandsIn;
    }

    public int nextCommandId()
    {
        return commandsIn++;
    }

    final void identify(Method cmd)
    {
        if (!incomingInit)
        {
            throw new IllegalStateException();
        }

        int id = nextCommandId();
        cmd.setId(id);

        if(LOGGER.isDebugEnabled())
        {
            LOGGER.debug("identify: ch={}, commandId={}", this.channel, id);
        }

        if ((id & 0xff) == 0)
        {
            flushProcessed(TIMELY_REPLY);
        }
    }

    public void processed(Method command)
    {
        processed(command.getId());
    }

    public void processed(int command)
    {
        processed(command, command);
    }

    public void processed(Range range)
    {

        processed(range.getLower(), range.getUpper());
    }

    public void processed(int lower, int upper)
    {
        if(LOGGER.isDebugEnabled())
        {
            LOGGER.debug("{} ch={} processed([{},{}]) {} {}", this, channel, lower, upper, syncPoint, maxProcessed);
        }

        boolean flush;
        synchronized (processedLock)
        {
            if(LOGGER.isDebugEnabled())
            {
                LOGGER.debug("{} processed: {}", this, processed);
            }

            if (ge(upper, commandsIn))
            {
                throw new IllegalArgumentException
                    ("range exceeds max received command-id: " + Range.newInstance(lower, upper));
            }

            processed.add(lower, upper);

            Range first = processed.getFirst();

            int flower = first.getLower();
            int fupper = first.getUpper();
            int old = maxProcessed;
            if (le(flower, maxProcessed + 1))
            {
                maxProcessed = max(maxProcessed, fupper);
            }
            boolean synced = ge(maxProcessed, syncPoint);
            flush = lt(old, syncPoint) && synced;
            if (synced)
            {
                syncPoint = maxProcessed;
            }
        }
        if (flush)
        {
            flushProcessed();
        }
    }

    void flushExpected()
    {
        RangeSet rs = RangeSetFactory.createRangeSet();
        synchronized (processedLock)
        {
            if (incomingInit)
            {
                rs.add(commandsIn);
            }
        }
        sessionExpected(rs, null);
    }

    public void flushProcessed(Option ... options)
    {
        RangeSet copy;
        synchronized (processedLock)
        {
            copy = processed.copy();
        }

        synchronized (commandsLock)
        {
            if (state == DETACHED || state == CLOSING || state == CLOSED)
            {
                return;
            }
            if (copy.size() > 0)
            {
	            sessionCompleted(copy, options);
            }
        }
    }

    void knownComplete(RangeSet kc)
    {
        if (kc.size() > 0)
        {
            synchronized (processedLock)
            {
                processed.subtract(kc) ;
            }
        }
    }

    void syncPoint()
    {
        int id = getCommandsIn() - 1;
        LOGGER.debug("{} synced to {}", this, id);
        boolean flush;
        synchronized (processedLock)
        {
            syncPoint = id;
            flush = ge(maxProcessed, syncPoint);
        }
        if (flush)
        {
            flushProcessed();
        }
    }

    protected boolean complete(int lower, int upper)
    {
        //avoid autoboxing
        if(LOGGER.isDebugEnabled())
        {
            LOGGER.debug("{} complete({}, {})", this, lower, upper);
        }
        synchronized (commandsLock)
        {
            int old = maxComplete;
            for (int id = max(maxComplete, lower); le(id, upper); id++)
            {
                Method m = removeCommand(id);
                if (m != null)
                {
                    commandBytes -= m.getBodySize();
                    m.complete();
                }
            }
            if (le(lower, maxComplete + 1))
            {
                maxComplete = max(maxComplete, upper);
            }

            if(LOGGER.isDebugEnabled())
            {
                LOGGER.debug("{}   commands remaining: {}", this, commandsOut - maxComplete);
            }

            commandsLock.notifyAll();
            return gt(maxComplete, old);
        }
    }

    void received(Method m)
    {
        m.delegate(this, delegate);
    }

    private void send(Method m)
    {
        m.setChannel(channel);
        connection.send(m);

        if (!m.isBatch())
        {
            connection.flush();
        }
    }

    protected boolean isFull(int id)
    {
        return isCommandsFull(id) || isBytesFull();
    }

    protected boolean isBytesFull()
    {
        return commandBytes >= byteLimit;
    }

    protected boolean isCommandsFull(int id)
    {
        return id - maxComplete >= commandLimit;
    }

    public void invoke(Method m)
    {
        invoke(m,(Runnable)null);
    }

    public void invoke(Method m, Runnable postIdSettingAction)
    {
        if (m.getEncodedTrack() == Frame.L4)
        {
            if (m.hasPayload())
            {
                acquireCredit();
            }

            synchronized (commandsLock)
            {
                if (state == DETACHED && m.isUnreliable())
                {
                    Thread current = Thread.currentThread();
                    if (!current.equals(resumer))
                    {
                        return;
                    }
                }

                if (state != OPEN && state != CLOSED && state != CLOSING)
                {
                    Thread current = Thread.currentThread();
                    if (!current.equals(resumer) )
                    {
                        Waiter w = new Waiter(commandsLock, timeout);
                        while (w.hasTime() && (state != OPEN && state != CLOSED))
                        {
                            checkFailoverRequired("Command was interrupted because of failover, before being sent");
                            w.await();
                        }
                    }
                }

                switch (state)
                {
                case OPEN:
                    break;
                case RESUMING:
                    Thread current = Thread.currentThread();
                    if (!current.equals(resumer))
                    {
                        throw new SessionException
                            ("timed out waiting for resume to finish");
                    }
                    break;
                case CLOSING:
                case CLOSED:
                    ExecutionException exc = getException();
                    if (exc != null)
                    {
                        throw new SessionException(exc);
                    }
                    else
                    {
                        throw new SessionClosedException();
                    }
                default:
                    throw new SessionException
                        (String.format
                         ("timed out waiting for session to become open " +
                          "(state=%s)", state));
                }

                int next;
                next = commandsOut++;
                m.setId(next);
                if(postIdSettingAction != null)
                {
                    postIdSettingAction.run();
                }

                if (isFull(next))
                {
                    Waiter w = new Waiter(commandsLock, timeout);
                    while (w.hasTime() && isFull(next) && state != CLOSED)
                    {
                        if (state == OPEN || state == RESUMING)
                        {
                            try
                            {
                                sessionFlush(COMPLETED);
                            }
                            catch (SenderException e)
                            {
                                if (!closing)
                                {
                                    // if expiry is > 0 then this will
                                    // happen again on resume
                                    LOGGER.error("error sending flush (full replay buffer)", e);
                                }
                                else
                                {
                                    e.rethrow();
                                }
                            }
                        }
                        checkFailoverRequired("Command was interrupted because of failover, before being sent");
                        w.await();
                    }
                }

                if (state == CLOSED)
                {
                    ExecutionException exc = getException();
                    if (exc != null)
                    {
                        throw new SessionException(exc);
                    }
                    else
                    {
                        throw new SessionClosedException();
                    }
                }

                if (isFull(next))
                {
                    throw new SessionException("timed out waiting for completion");
                }

                if (next == 0)
                {
                    sessionCommandPoint(0, 0);
                }

                boolean replayTransfer = !_isNoReplay && !closing && !transacted &&
                                         m instanceof MessageTransfer &&
                                         ! m.isUnreliable();

                if ((replayTransfer) || m.hasCompletionListener())
                {
                    setCommand(next, m);
                    commandBytes += m.getBodySize();
                }
                if (autoSync)
                {
                    m.setSync(true);
                }
                needSync = !m.isSync();

                try
                {
                    send(m);
                }
                catch (SenderException e)
                {
                    if (!closing)
                    {
                        // if we are not closing then this will happen
                        // again on resume
                        LOGGER.error("error sending command", e);
                    }
                    else
                    {
                        e.rethrow();
                    }
                }
                if (autoSync)
                {
                    sync();
                }

                // flush every 64K commands to avoid ambiguity on
                // wraparound
                if (shouldIssueFlush(next))
                {
                    try
                    {
                        sessionFlush(COMPLETED);
                    }
                    catch (SenderException e)
                    {
                        if (!closing)
                        {
                            // if expiry is > 0 then this will happen
                            // again on resume
                            LOGGER.error("error sending flush (periodic)", e);
                        }
                        else
                        {
                            e.rethrow();
                        }
                    }
                }
            }
        }
        else
        {
            send(m);
        }
    }

    private void checkFailoverRequired(String message)
    {
        if (_failoverRequired.get())
        {
            throw new SessionException(message);
        }
    }

    protected boolean shouldIssueFlush(int next)
    {
        return (next % 65536) == 0;
    }

    public void sync()
    {
        sync(timeout);
    }

    public void sync(long timeout)
    {
        LOGGER.debug("{} sync()", this);
        synchronized (commandsLock)
        {
            int point = commandsOut - 1;

            if (needSync && lt(maxComplete, point))
            {
                executionSync(SYNC);
            }

            Waiter w = new Waiter(commandsLock, timeout);
            while (w.hasTime() && state != CLOSED && lt(maxComplete, point))
            {
                checkFailoverRequired("Session sync was interrupted by failover.");
                if(LOGGER.isDebugEnabled())
                {
                    LOGGER.debug("{}   waiting for[{}]: {}, {}", this, point, maxComplete, commands);
                }
                w.await();
            }

            if (lt(maxComplete, point))
            {
                if (state != CLOSED)
                {
                    throw new SessionException(
		                    String.format("timed out waiting for sync: complete = %s, point = %s",
		                            maxComplete, point));
                }
                else
                {
                    ExecutionException ee = getException();
                    if (ee != null)
                    {
                        throw new SessionException(ee);
                    }
                }
            }
        }
    }

    private Map<Integer,ResultFuture<?>> results = new HashMap<Integer,ResultFuture<?>>();
    private ExecutionException exception = null;

    void result(int command, Struct result)
    {
        ResultFuture<?> future;
        synchronized (results)
        {
            future = results.remove(command);
        }

        if (future != null)
        {
            future.set(result);
        }
        else
        {
            LOGGER.warn("Received a response to a command" +
                     " that's no longer valid on the client side." +
                     " [ command id : {} , result : {} ]", command, result);
        }
    }

    void setException(ExecutionException exc)
    {
        synchronized (results)
        {
            if (exception != null)
            {
                throw new IllegalStateException(
                        String.format("too many exceptions: %s, %s", exception, exc));
            }
            exception = exc;
        }
    }

    ExecutionException getException()
    {
        synchronized (results)
        {
            return exception;
        }
    }

    protected <T> Future<T> invoke(Method m, Class<T> klass)
    {
        synchronized (commandsLock)
        {
            int command = commandsOut;
            ResultFuture<T> future = new ResultFuture<T>(klass);
            synchronized (results)
            {
                results.put(command, future);
            }
            invoke(m);
            return future;
        }
    }

    private class ResultFuture<T> implements Future<T>
    {

        private final Class<T> klass;
        private T result;

        private ResultFuture(Class<T> klass)
        {
            this.klass = klass;
        }

        private void set(Struct result)
        {
            synchronized (this)
            {
                this.result = klass.cast(result);
                notifyAll();
            }
        }

        public T get(long timeout)
        {
            synchronized (this)
            {
                Waiter w = new Waiter(this, timeout);
                while (w.hasTime() && state != CLOSED && !isDone())
                {
                    checkFailoverRequired("Operation was interrupted by failover.");
                    LOGGER.debug("{} waiting for result: {}", Session.this, this);
                    w.await();
                }
            }

            if (isDone())
            {
                return result;
            }
            else if (state == CLOSED)
            {
                ExecutionException ex = getException();
                if(ex == null)
                {
                    throw new SessionClosedException();
                }
                throw new SessionException(ex);
            }
            else
            {
                throw new SessionException(
                        String.format("%s timed out waiting for result: %s",
                                   Session.this, this));
            }
        }

        public T get()
        {
            return get(timeout);
        }

        public boolean isDone()
        {
            return result != null;
        }

        public String toString()
        {
            return String.format("Future(%s)", isDone() ? result : klass);
        }

    }

    public final void messageTransfer(String destination,
                                      MessageAcceptMode acceptMode,
                                      MessageAcquireMode acquireMode,
                                      Header header,
                                      byte[] body,
                                      Option ... _options) {
        messageTransfer(destination, acceptMode, acquireMode, header,
                        ByteBuffer.wrap(body), _options);
    }

    public final void messageTransfer(String destination,
                                      MessageAcceptMode acceptMode,
                                      MessageAcquireMode acquireMode,
                                      Header header,
                                      String body,
                                      Option ... _options) {
        messageTransfer(destination, acceptMode, acquireMode, header,
                        toUTF8(body), _options);
    }

    public void close()
    {
        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug("Closing [{}] in state [{}]", this, state);
        }
        synchronized (commandsLock)
        {
            switch(state)
            {
                case DETACHED:
                    state = CLOSED;
                    delegate.closed(this);
                    connection.removeSession(this);
                    listener.closed(this);
                    break;
                case CLOSED:
                    break;
                default:
                    state = CLOSING;
                    setClose(true);
                    sessionRequestTimeout(0);
                    sessionDetach(name.getBytes());
                    awaitClose();
            }
        }
    }

    protected void awaitClose()
    {
        Waiter w = new Waiter(commandsLock, timeout);
        while (w.hasTime() && state != CLOSED)
        {
            checkFailoverRequired("close() was interrupted by failover.");
            w.await();
        }

        if (state != CLOSED)
        {
            throw new SessionException("close() timed out");
        }
    }

    public void exception(Throwable t)
    {
        LOGGER.error("caught exception", t);
    }

    public void closed()
    {
        synchronized (commandsLock)
        {
            if (closing || getException() != null)
            {
                state = CLOSED;
            }
            else
            {
                state = DETACHED;
            }

            commandsLock.notifyAll();

            synchronized (results)
            {
                for (ResultFuture<?> result : results.values())
                {
                    synchronized(result)
                    {
                        result.notifyAll();
                    }
                }
            }
            if(state == CLOSED)
            {
                delegate.closed(this);
            }
            else
            {
                delegate.detached(this);
            }
        }

        if(state == CLOSED)
        {
            connection.removeSession(this);
            listener.closed(this);
        }
    }

    public boolean isClosing()
    {
        return state == CLOSED || state == CLOSING;
    }

    public String toString()
    {
        return String.format("ssn:%s", name);
    }

    public void setTransacted(boolean b) {
        this.transacted = b;
    }

    public boolean isTransacted(){
        return transacted;
    }

    public void setDetachCode(SessionDetachCode dtc)
    {
        this.detachCode = dtc;
    }

    public SessionDetachCode getDetachCode()
    {
        return this.detachCode;
    }

    public void awaitOpen()
    {
        switch (state)
        {
        case NEW:
            synchronized(stateLock)
            {
                Waiter w = new Waiter(stateLock, timeout);
                while (w.hasTime() && state == NEW)
                {
                    checkFailoverRequired("Session opening was interrupted by failover.");
                    w.await();
                }
            }

            if (state != OPEN)
            {
                throw new SessionException("Timed out waiting for Session to open");
            }
            break;
        case DETACHED:
        case CLOSING:
        case CLOSED:
            throw new SessionException("Session closed");
        default :
            break;
        }
    }

    public Object getStateLock()
    {
        return stateLock;
    }

    protected void notifyFailoverRequired()
    {
        //ensure any operations waiting are aborted to
        //prevent them waiting for timeout for 60 seconds
        //and possibly preventing failover proceeding
        _failoverRequired.set(true);
        synchronized (commandsLock)
        {
            commandsLock.notifyAll();
        }
        synchronized (results)
        {
            for (ResultFuture<?> result : results.values())
            {
                synchronized(result)
                {
                    result.notifyAll();
                }
            }
        }
    }

    /**
     * An auxiliary method for test purposes only
     * @return true if flow is blocked
     */
    public boolean isFlowBlocked()
    {
        return flowControl && credit.availablePermits() == 0;
    }
}
