/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import org.redkale.util.*;

/**
 *
 * <p>
 * 详情见: http://www.redkale.org
 *
 * @author zhangjx
 * @param <C> Context的子类型
 * @param <R> Request的子类型
 * @param <P> Response的子类型
 */
public abstract class PrepareServlet<K extends Serializable, C extends Context, R extends Request<C>, P extends Response<C, R>, S extends Servlet<C, R, P>> extends Servlet<C, R, P> {

    protected final AtomicLong executeCounter = new AtomicLong(); //执行请求次数

    protected final AtomicLong illRequestCounter = new AtomicLong(); //错误请求次数

    protected final Set<S> servlets = new HashSet<>();

    protected final Map<K, S> mappings = new HashMap<>();

    public abstract void addServlet(S servlet, Object attachment, AnyValue conf, K... mappings);

    public final void prepare(final ByteBuffer buffer, final R request, final P response) throws IOException {
        executeCounter.incrementAndGet();
        final int rs = request.readHeader(buffer);
        if (rs < 0) {
            response.context.offerBuffer(buffer);
            if (rs != Integer.MIN_VALUE) illRequestCounter.incrementAndGet();
            response.finish(true);
        } else if (rs == 0) {
            response.context.offerBuffer(buffer);
            request.prepare();
            this.execute(request, response);
        } else {
            buffer.clear();
            final AtomicInteger ai = new AtomicInteger(rs);
            request.channel.read(buffer, buffer, new CompletionHandler<Integer, ByteBuffer>() {

                @Override
                public void completed(Integer result, ByteBuffer attachment) {
                    buffer.flip();
                    ai.addAndGet(-request.readBody(buffer));
                    if (ai.get() > 0) {
                        buffer.clear();
                        request.channel.read(buffer, buffer, this);
                    } else {
                        response.context.offerBuffer(buffer);
                        request.prepare();
                        try {
                            execute(request, response);
                        } catch (Exception e) {
                            illRequestCounter.incrementAndGet();
                            response.finish(true);
                            request.context.logger.log(Level.WARNING, "prepare servlet abort, forece to close channel ", e);
                        }
                    }
                }

                @Override
                public void failed(Throwable exc, ByteBuffer attachment) {
                    illRequestCounter.incrementAndGet();
                    response.context.offerBuffer(buffer);
                    response.finish(true);
                    if (exc != null) request.context.logger.log(Level.FINER, "Servlet read channel erroneous, forece to close channel ", exc);
                }
            });
        }
    }

    protected AnyValue getServletConf(Servlet servlet) {
        return servlet._conf;
    }

    protected void setServletConf(Servlet servlet, AnyValue conf) {
        servlet._conf = conf;
    }
}
