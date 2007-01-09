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
package org.apache.qpid.framing;

import org.apache.log4j.Logger;
import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;

import java.util.HashMap;
import java.util.Map;

public class AMQDataBlockDecoder
{
    Logger _logger = Logger.getLogger(AMQDataBlockDecoder.class);

    private final Map _supportedBodies = new HashMap();

    private final static BodyFactory[] _bodiesSupported = new BodyFactory[Byte.MAX_VALUE];
    static
    {
        _bodiesSupported[AMQMethodBody.TYPE] = AMQMethodBodyFactory.getInstance();
        _bodiesSupported[ContentHeaderBody.TYPE] = ContentHeaderBodyFactory.getInstance();
        _bodiesSupported[ContentBody.TYPE] = ContentBodyFactory.getInstance();
        _bodiesSupported[HeartbeatBody.TYPE] = new HeartbeatBodyFactory();
    }

    public AMQDataBlockDecoder()
    {
    }

    public boolean decodable(IoSession session, ByteBuffer in) throws AMQFrameDecodingException
    {
        final int remainingAfterAttributes = in.remaining() - (1 + 2 + 4 + 1);
        // type, channel, body length and end byte
        if (remainingAfterAttributes < 0)
        {
            return false;
        }

        final byte type = in.get();
        final int channel = in.getUnsignedShort();
        final long bodySize = in.getUnsignedInt();

        // bodySize can be zero
        if (type <= 0 || channel < 0 || bodySize < 0)
        {
            throw new AMQFrameDecodingException("Undecodable frame: type = " + type + " channel = " + channel +
                                                " bodySize = " + bodySize);
        }

        return (remainingAfterAttributes >= bodySize);

    }

    private boolean isSupportedFrameType(byte frameType)
    {
        final boolean result = _bodiesSupported[frameType] != null;

        if (!result)
        {
        	_logger.warn("AMQDataBlockDecoder does not handle frame type " + frameType);
        }

        return result;
    }

    protected Object createAndPopulateFrame(ByteBuffer in)
                    throws AMQFrameDecodingException, AMQProtocolVersionException
    {
        final byte type = in.get();
        BodyFactory bodyFactory = _bodiesSupported[type];
        if (!isSupportedFrameType(type))
        {
            throw new AMQFrameDecodingException("Unsupported frame type: " + type);
        }
        final int channel = in.getUnsignedShort();
        final long bodySize = in.getUnsignedInt();

        /*
        if (bodyFactory == null)
        {
            throw new AMQFrameDecodingException("Unsupported body type: " + type);
        }
        */
        AMQFrame frame = new AMQFrame(in, channel, bodySize, bodyFactory);

        
        byte marker = in.get();
        if ((marker & 0xFF) != 0xCE)
        {
            throw new AMQFrameDecodingException("End of frame marker not found. Read " + marker + " length=" + bodySize + " type=" + type);
        }
        return frame;
    }

    public void decode(IoSession session, ByteBuffer in, ProtocolDecoderOutput out)
        throws Exception
    {
        out.write(createAndPopulateFrame(in));
    }
}
