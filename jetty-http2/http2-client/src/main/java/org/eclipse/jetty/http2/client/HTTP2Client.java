//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2FlowControl;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.ErrorCode;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.parser.PrefaceParser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

public class HTTP2Client extends ContainerLifeCycle
{
    private final Queue<Session> sessions = new ConcurrentLinkedQueue<>();
    private final Executor executor;
    private final Scheduler scheduler;
    private final SelectorManager selector;
    private final ByteBufferPool byteBufferPool;

    public HTTP2Client()
    {
        this(new QueuedThreadPool());
    }

    public HTTP2Client(Executor executor)
    {
        this.executor = executor;
        addBean(executor);
        this.scheduler = new ScheduledExecutorScheduler();
        addBean(scheduler, true);
        this.selector = new ClientSelectorManager(executor, scheduler);
        addBean(selector, true);
        this.byteBufferPool = new MappedByteBufferPool();
        addBean(byteBufferPool, true);
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
        super.doStop();
    }

    public void connect(InetSocketAddress address, Session.Listener listener, Promise<Session> promise)
    {
        try
        {
            SocketChannel channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);
            channel.connect(address);
            selector.connect(channel, new Context(listener, promise));
        }
        catch (Throwable x)
        {
            promise.failed(x);
        }
    }

    private void closeConnections()
    {
        for (Session session : sessions)
            session.close(ErrorCode.NO_ERROR, null, Callback.Adapter.INSTANCE);
        sessions.clear();
    }

    private class ClientSelectorManager extends SelectorManager
    {
        private ClientSelectorManager(Executor executor, Scheduler scheduler)
        {
            super(executor, scheduler);
        }

        @Override
        protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
        {
            return new SelectChannelEndPoint(channel, selector, selectionKey, getScheduler(), 30000);
        }

        @Override
        public Connection newConnection(SocketChannel channel, EndPoint endpoint, Object attachment) throws IOException
        {
            Context context = (Context)attachment;
            Generator generator = new Generator(byteBufferPool, 4096);
            HTTP2Session session = new HTTP2ClientSession(endpoint, generator, context.listener, new HTTP2FlowControl(), 65535);
            Parser parser = new Parser(byteBufferPool, session);
            Connection connection = new HTTP2ClientConnection(byteBufferPool, getExecutor(), endpoint, parser, 8192, session);
            context.promise.succeeded(session);
            return connection;
        }
    }

    private class HTTP2ClientConnection extends HTTP2Connection
    {
        private final Session session;

        public HTTP2ClientConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endpoint, Parser parser, int bufferSize, Session session)
        {
            super(byteBufferPool, executor, endpoint, parser, bufferSize);
            this.session = session;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            sessions.offer(session);
            getEndPoint().write(Callback.Adapter.INSTANCE, ByteBuffer.wrap(PrefaceParser.PREFACE_BYTES));
        }

        @Override
        public void onClose()
        {
            super.onClose();
            sessions.remove(session);
        }
    }

    private class Context
    {
        private final Session.Listener listener;
        private final Promise<Session> promise;

        private Context(Session.Listener listener, Promise<Session> promise)
        {
            this.listener = listener;
            this.promise = promise;
        }
    }
}
