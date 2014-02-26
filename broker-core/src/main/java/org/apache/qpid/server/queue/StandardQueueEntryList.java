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
package org.apache.qpid.server.queue;

import org.apache.qpid.server.message.ServerMessage;

public class StandardQueueEntryList extends OrderedQueueEntryList
{

    private static final HeadCreator HEAD_CREATOR = new HeadCreator()
    {
        @Override
        public StandardQueueEntry createHead(final QueueEntryList list)
        {
            return new StandardQueueEntry((StandardQueueEntryList) list);
        }
    };

    public StandardQueueEntryList(final StandardQueue queue)
    {
        super(queue, HEAD_CREATOR);
    }


    protected StandardQueueEntry createQueueEntry(ServerMessage<?> message)
    {
        return new StandardQueueEntry(this, message);
    }

    static class Factory implements QueueEntryListFactory
    {

        public StandardQueueEntryList createQueueEntryList(AMQQueue<?> queue)
        {
            return new StandardQueueEntryList((StandardQueue) queue);
        }
    }

}
