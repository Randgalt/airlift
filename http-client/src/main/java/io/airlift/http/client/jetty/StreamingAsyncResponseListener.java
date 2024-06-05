package io.airlift.http.client.jetty;

import com.google.errorprone.annotations.ThreadSafe;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static java.util.Objects.requireNonNull;

@ThreadSafe
class StreamingAsyncResponseListener
        implements Response.Listener
{
    private final JettyResponseFuture<?, ?> future;
    private final BuffersInputStream buffersInputStream = new BuffersInputStream();
    @GuardedBy("this")
    private boolean firstBuffer = true;

    StreamingAsyncResponseListener(JettyResponseFuture<?, ?> future)
    {
        this.future = requireNonNull(future, "future is null");
    }

    @Override
    public synchronized void onContent(Response response, ByteBuffer content)
    {
        if (!content.hasRemaining()) {
            return;
        }

        // Jetty owns the content buffer, we must make a copy
        ByteBuffer copy = ByteBuffer.allocate(content.remaining());
        copy.put(content);
        copy.rewind();

        buffersInputStream.addBuffer(copy);

        if (firstBuffer) {
            firstBuffer = false;
            future.completed(response, buffersInputStream);
        }
    }

    @Override
    public synchronized void onFailure(Response response, Throwable failure)
    {
        future.failed(failure);
    }

    @Override
    public synchronized void onComplete(Result result)
    {
        buffersInputStream.addTerminal();
    }

    @ThreadSafe
    private static class BuffersInputStream
            extends InputStream
    {
        private static final ByteBuffer TERMINAL = ByteBuffer.allocate(0);

        private final BlockingQueue<ByteBuffer> buffers = new LinkedBlockingQueue<>();
        private volatile ByteBuffer currentByteBuffer;

        public void addBuffer(ByteBuffer byteBuffer)
        {
            buffers.add(byteBuffer);
        }

        public void addTerminal()
        {
            buffers.add(TERMINAL);
        }

        @Override
        public synchronized int read()
                throws IOException
        {
            if (!checkBuffer()) {
                return -1;
            }

            return currentByteBuffer.get() & 0xff;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len)
                throws IOException
        {
            if (!checkBuffer()) {
                return -1;
            }

            int readLength = Math.min(len, currentByteBuffer.remaining());
            currentByteBuffer.get(b, off, readLength);
            return readLength;
        }

        private boolean checkBuffer()
                throws IOException
        {
            if (currentByteBuffer == TERMINAL) {
                return false;
            }

            if ((currentByteBuffer != null) && currentByteBuffer.hasRemaining()) {
                return true;
            }

            try {
                currentByteBuffer = buffers.take();
                return checkBuffer();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }
}
