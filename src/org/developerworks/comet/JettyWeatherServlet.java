package org.developerworks.comet;

import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.io.SyndFeedInput;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.mortbay.util.ajax.Continuation;
import org.mortbay.util.ajax.ContinuationSupport;

public class JettyWeatherServlet extends HttpServlet {

    private MessageSender messageSender = null;
    private static final Integer TIMEOUT = 5 * 1000;

    @Override
    public void destroy() {
        messageSender.stop();
        messageSender = null;
    }

    @Override
    public void init() throws ServletException {
        messageSender = new MessageSender();
        Thread messageSenderThread =
                new Thread(messageSender, "MessageSender[" + getServletContext().getContextPath() + "]");
        messageSenderThread.setDaemon(true);
        messageSenderThread.start();

    }

    public void begin(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        request.setAttribute("org.apache.tomcat.comet", Boolean.TRUE);
        request.setAttribute("org.apache.tomcat.comet.timeout", TIMEOUT);
        log("Begin for session: " + request.getSession(true).getId());
        Weatherman weatherman = new Weatherman(95118, 32408);
        weatherman.start();
        messageSender.setConnection(response);
    }

    public void end(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        synchronized (request) {
            request.removeAttribute("org.apache.tomcat.comet");

            Continuation continuation = ContinuationSupport.getContinuation(request, request);
            if (continuation.isPending()) {
                continuation.resume();
            }
        }
        log("End for session: " + request.getSession(true).getId());
    }

    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        log("Error for session: " + request.getSession(true).getId());
        end(request, response);
    }

    public boolean read(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        synchronized (request) {
            // TODO: wrap response so we can reset timeout on writes.

            Continuation continuation = ContinuationSupport.getContinuation(request, request);

            if (!continuation.isPending()) {
                begin(request, response);
            }

            Integer timeout = (Integer) request.getAttribute("org.apache.tomcat.comet.timeout");
            boolean resumed = continuation.suspend(timeout == null ? 10000 : timeout.intValue());

            if (!resumed) {
                error(request, response);
            }
        }
    }

    public void setTimeout(HttpServletRequest request, HttpServletResponse response, int timeout) throws IOException, ServletException,
            UnsupportedOperationException {
        request.setAttribute("org.apache.tomcat.comet.timeout", new Integer(timeout));
    }

   private class Weatherman {

        private final List<URL> zipCodes;
        private final String YAHOO_WEATHER = "http://weather.yahooapis.com/forecastrss?p=";

        public Weatherman(Integer... zips) {
            zipCodes = new ArrayList<URL>(zips.length);
            for (Integer zip : zips) {
                try {
                    zipCodes.add(new URL(YAHOO_WEATHER + zip));
                } catch (Exception e) {
                    // dont add it if it sucks
                }
            }
        }

        public void start() {
            Runnable r = new Runnable() {

                public void run() {
                    int i = 0;
                    while (i >= 0) {
                        int j = i % zipCodes.size();
                        SyndFeedInput input = new SyndFeedInput();
                        try {
                            SyndFeed feed = input.build(new InputStreamReader(zipCodes.get(j).openStream()));
                            SyndEntry entry = (SyndEntry) feed.getEntries().get(0);
                            messageSender.send(entryToHtml(entry));
                            Thread.sleep(30000L);
                        } catch (Exception e) {
                            // just eat it, eat it
                        }
                        i++;
                    }
                }
            };
            Thread t = new Thread(r);
            t.start();
        }
        private String entryToHtml(SyndEntry entry){
            StringBuilder html = new StringBuilder("<h2>");
            html.append(entry.getTitle());
            html.append("</h2>");
            html.append(entry.getDescription().getValue());
            return html.toString();
        }
    }

   private class MessageSender implements Runnable {

        protected boolean running = true;
        protected final ArrayList<String> messages = new ArrayList<String>();
        private ServletResponse connection;

        private synchronized void setConnection(ServletResponse connection){
            this.connection = connection;
            notify();
        }

        public void stop() {
            running = false;
        }

        /**
         * Add message for sending.
         */
        public void send(String message) {
            synchronized (messages) {
                messages.add(message);
                log("Message added #messages=" + messages.size());
                messages.notify();
            }
        }

        public void run() {
            while (running) {
                if (messages.size() == 0) {
                    try {
                        synchronized (messages) {
                            messages.wait();
                        }
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
                String[] pendingMessages = null;
                synchronized (messages) {
                    pendingMessages = messages.toArray(new String[0]);
                    messages.clear();
                }
                try {
                    if (connection == null){
                        try{
                            synchronized(this){
                                wait();
                            }
                        } catch (InterruptedException e){
                            // Ignore
                        }
                    }
                    PrintWriter writer = connection.getWriter();
                    for (int j = 0; j < pendingMessages.length; j++) {
                        final String forecast = pendingMessages[j] + "<br>";
                        writer.println(forecast);
                        log("Writing:" + forecast);
                    }
                    writer.flush();
                    writer.close();
                    connection = null;
                    log("Closing connection");
                } catch (IOException e) {
                    log("IOExeption sending message", e);
                }
            }
        }
    }
}


