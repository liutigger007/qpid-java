/* Licensed to the Apache Software Foundation (ASF) under one
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
 */
package org.apache.qpid.client.latency;

import org.apache.qpid.client.AMQConnection;
import org.apache.qpid.client.message.TestMessageFactory;
import org.apache.qpid.client.perf.Options;
import org.apache.qpid.requestreply.InitialContextHelper;
import org.apache.qpidity.transport.network.nio.NioSender;

import javax.jms.*;
import javax.naming.Context;

/**
 *
 *
 */
public class MessageProducer  extends Options
{
    private BytesMessage _payload;
    private javax.jms.MessageProducer _producer;
    private javax.jms.MessageConsumer _consumer;
    private AMQConnection _connection;
   private void init()
    {
        this.parseOptions();
        try
        {
            Context context = InitialContextHelper.getInitialContext("");
            ConnectionFactory factory = (ConnectionFactory) context.lookup("local");
            _connection = (AMQConnection) factory.createConnection("guest","guest");
            Destination dest = Boolean.getBoolean("useQueue")? (Destination) context.lookup("testQueue") :
                       (Destination) context.lookup("testTopic");
             Destination syncQueue   = (Destination) context.lookup("syncQueue");
             _connection.start();
            Session session = _connection.createSession(_transacted, Session.AUTO_ACKNOWLEDGE);
            _payload = TestMessageFactory.newBytesMessage(session, _messageSize);
            _producer = session.createProducer(dest);
            _consumer = session.createConsumer(syncQueue);
            // this should speedup the message producer
            _producer.setDisableMessageTimestamp(true);
             System.out.println("Init end" );
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void run()
    {
        try
        {
            System.out.println("Sending " + _logFrequency + " messages");

          // NioSender.setStartBatching();
            long startTime = System.currentTimeMillis();
            for(int i =0; i < _logFrequency; i++ )
            {
                _producer.send(_payload, DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, 0);
            }
            long endProducing = System.currentTimeMillis();
            double throughput = (_logFrequency * 1000.0) / (endProducing - startTime);
            System.out.println("The producer throughput is: " + throughput + " msg/s");

          //  startTime = System.currentTimeMillis();
          //  NioSender.purge();
          //  endProducing = System.currentTimeMillis();
          //  throughput = (_logFrequency * 1000.0) / (endProducing - startTime);
          //  System.out.println("The NIO throughput is: " + throughput + " msg/s");


            // now wait for the sync message
            _consumer.receive();
            // this is done 
            long endTime = System.currentTimeMillis();
            System.out.println("Time to send and receive " + _logFrequency + " messages is: " + (endTime - startTime) );
            double latency = ( (endTime - startTime)  * 1.0) /_logFrequency;
            System.out.println("The latency is " + latency + " milli secs" );
            _connection.close();
        }
        catch (JMSException e)
        {
            e.printStackTrace();
        }
    }

    public static void main(String[] args)
    {
        try
        {
            MessageProducer test = new MessageProducer();
            test.init();
            test.run();
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
    }
}
