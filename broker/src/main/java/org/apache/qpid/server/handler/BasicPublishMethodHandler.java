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
package org.apache.qpid.server.handler;

import org.apache.log4j.Logger;
import org.apache.qpid.AMQException;
import org.apache.qpid.exchange.ExchangeDefaults;
import org.apache.qpid.framing.BasicPublishBody;
import org.apache.qpid.protocol.AMQMethodEvent;
import org.apache.qpid.server.AMQChannel;
import org.apache.qpid.server.exchange.Exchange;
import org.apache.qpid.server.protocol.AMQProtocolSession;
import org.apache.qpid.server.state.AMQStateManager;
import org.apache.qpid.server.state.StateAwareMethodListener;
import org.apache.qpid.server.virtualhost.VirtualHost;

public class BasicPublishMethodHandler  implements StateAwareMethodListener<BasicPublishBody>
{
    private static final Logger _log = Logger.getLogger(BasicPublishMethodHandler.class);

    private static final BasicPublishMethodHandler _instance = new BasicPublishMethodHandler();


    public static BasicPublishMethodHandler getInstance()
    {
        return _instance;
    }

    private BasicPublishMethodHandler()
    {
    }

    public void methodReceived(AMQStateManager stateManager, AMQMethodEvent<BasicPublishBody> evt) throws AMQException
    {
        AMQProtocolSession session = stateManager.getProtocolSession();

        final BasicPublishBody body = evt.getMethod();

        if (_log.isDebugEnabled())
        {
            _log.debug("Publish received on channel " + evt.getChannelId());
        }

        // TODO: check the delivery tag field details - is it unique across the broker or per subscriber?
        if (body.exchange == null)
        {
            body.exchange = ExchangeDefaults.DIRECT_EXCHANGE_NAME;

        }
        VirtualHost vHost = session.getVirtualHost();
        Exchange e = vHost.getExchangeRegistry().getExchange(body.exchange);
        // if the exchange does not exist we raise a channel exception
        if (e == null)
        {
            throw body.getChannelException(500, "Unknown exchange name");

        }
        else
        {
            // The partially populated BasicDeliver frame plus the received route body
            // is stored in the channel. Once the final body frame has been received
            // it is routed to the exchange.
            AMQChannel channel = session.getChannel(evt.getChannelId());
            channel.setPublishFrame(body, session);
        }
    }
}


