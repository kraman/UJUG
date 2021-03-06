package com.restfully.shop.services;

import org.judcon.CachingService;
import org.switchyard.quickstarts.demo.multiapp.Order;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Path("/jms")
public class JmsResource {	
	CachingService caching;
	
	Connection conn;
    Session session;
    Queue queue;
    Queue replyQueue;


	public JmsResource() {
		caching = new CachingService();
	}

	@POST
	@Consumes("application/xml")
	public Response createOrder(InputStream is) {
		try {
			Order order = readOrder(is);
			System.out.println("--- RESTful JmsResource received a POST containing order: " + order );
			caching.put(order, Integer.toString(order.getId()));
			sendMessageOverJMS(order);
			Response response = Response.created(URI.create("/jms/" + order.getId()))
					.build();
			return response;
		} catch (Exception e){
			throw new RuntimeException(e);
		}
	}
	
	@GET
	@Path("{id}")
	@Produces("application/xml")
	public StreamingOutput getOrder(@PathParam("id") String id) {
		System.out.println("--- RESTful JmsResource received a GET requesting order: " + id );
		final Order order = (Order)caching.get(id);
		if (order == null) {
			throw new WebApplicationException(Response.Status.NOT_FOUND);
		}
		return new StreamingOutput() {
			public void write(OutputStream outputStream) throws IOException,
					WebApplicationException {
				outputOrder(outputStream, order);
			}
		};
	}

	protected void outputOrder(OutputStream os, Order order)
			throws IOException {
		PrintStream writer = new PrintStream(os);
		writer.println("<order id=\"" + order.getId() + "\">");
		writer.println("   <order-id>" + order.getOrderId() + "</order-id>");
		writer.println("   <item-id>" + order.getItemId() + "</item-id>");
		writer.println("   <quantity>" + order.getQuantity() + "</quantity>");
		writer.println("</order>");
	}

	protected Order readOrder(InputStream is) {
		try {
			DocumentBuilder builder = DocumentBuilderFactory.newInstance()
					.newDocumentBuilder();
			Document doc = builder.parse(is);
			Element root = doc.getDocumentElement();
			Order order = new Order();
			if (root.getAttribute("id") != null
					&& !root.getAttribute("id").trim().equals(""))
				order.setId(Integer.valueOf(root.getAttribute("id")));
			NodeList nodes = root.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				Element element = (Element) nodes.item(i);
				if (element.getTagName().equals("order-id")) {
					order.setOrderId(element.getTextContent());
				} else if (element.getTagName().equals("item-id")) {
					order.setItemId(element.getTextContent());
				} else if (element.getTagName().equals("quantity")) {
					order.setQuantity(Integer.parseInt(element.getTextContent()));
				} 
			}
			return order;
		} catch (Exception e) {
			throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
		}
	}
	
	public void sendMessageOverJMS(Order order) throws JMSException, NamingException {
        MessageProducer producer = null;

    	setupJMSConnection();
        try {
            TextMessage message = null;

            producer = session.createProducer(queue);
            message = session.createTextMessage();
            String text = "<orders:submitOrder xmlns:orders=\"urn:switchyard-quickstart-demo:multiapp:1.0\">" + order.toString() + "</orders:submitOrder>";
            message.setText(text);
            producer.send(message);
            System.out.println("  RESTful JmsResource send JMS message:  " + message.getText());
            MessageConsumer consumer = session.createConsumer(replyQueue);
            System.out.println("Order submitted ... waiting for reply.");
            BytesMessage reply = (BytesMessage)consumer.receive(3000);
            
            if (reply == null) {
                System.out.println("  RESTful JmsResource received no reply");
            } else {
                byte[] buf = new byte[1024];
                int count = reply.readBytes(buf);
                String str = new String(buf, 0, count);
                System.out.println("  RESTful JmsResource received JMS reply: " + str);
            }
        } finally {
            if(producer != null) {
            	producer.close();
            }
            cleanupJMSConnection();
        }
    }

    public void setupJMSConnection() throws JMSException, NamingException
    {
    	InitialContext context = new InitialContext();
    	
    	ConnectionFactory cf = (ConnectionFactory) context.lookup("java:jboss/exported/jms/RemoteConnectionFactory");
    	queue = (Queue) context.lookup("OrderRequestQueue");
    	replyQueue = (Queue) context.lookup("OrderReplyQueue");
    	conn = cf.createConnection("guest", "guest");
    	session = conn.createSession(false, QueueSession.AUTO_ACKNOWLEDGE);
    	conn.start();
    }

    public void cleanupJMSConnection() throws JMSException
    {
        conn.stop();
        session.close();
        conn.close();
    }
}
