package com.globex.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.ServerWebSocket;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;

import java.io.IOException;
import java.util.HashMap;

public class Websockets extends Verticle {
	
	private ObjectMapper mapper = new ObjectMapper();
	private HashMap<String, String> users = new HashMap<String, String>(); //Add because I need the chat to send through the bus and normal messages haven't chat tag
	private HashMap<String, String> depID = new HashMap<String, String>(); //Add because the most easy way to obtain the deploymentID is when I deploy the verticle
	private HashMap<String, String> colors = new HashMap<String, String>();
	private String[] colorsArray = { "007AFF", "FF7000", "15E25F", "CFC700", "CFC700", "CF1100", "CF00BE", "F00" };
	private volatile int colorIndex = 0;
	Logger logger;
	

public void start() {
	  
	  logger = container.logger(); 
	  
	  vertx.createHttpServer().websocketHandler(new Handler<ServerWebSocket>() {
      public void handle(final ServerWebSocket ws) {
        if (ws.path().equals("/myapp")) {
          ws.dataHandler(new Handler<Buffer>() {
            public void handle(Buffer data) {
            	JsonNode message= null;
            	try {
					message= mapper.readTree(data.getBytes());  //message to Json
				} catch (IOException e) {
					e.printStackTrace();
				}
            	
            	if (!message.has("message")){ //Is the log in message (don't have message) 
            		//If don't exists this username or a chat with the same name...
            		if ((!users.containsKey(message.get("user").asText()))&&(!users.containsValue(message.get("user").asText()))
            				&&((!message.get("user").asText().equals(message.get("chat").asText())))&&(!message.get("user").asText().contains("?"))){ 
            			JsonObject config = newUser(message);
                		container.deployVerticle("com.globex.app.User", config, newVerticleUser(ws,config));
                		
            		}else{ //If the username exists it send a message and close the conexion         			
        	    		ws.writeTextFrame(newMessageDuplicatedUser());
            			ws.close();  
            		}
            			
            	}else{ //Is a normal message. It sends to the eventBus with the chat like label
            		JsonObject msg = new JsonObject();
            		msg.putString("user", message.get("user").asText());
            		msg.putString("message", message.get("message").asText());
            		msg.putString("color", colors.get(message.get("user").asText())); 
            		vertx.eventBus().publish(users.get(message.get("user").asText()), msg);
            	}
            }
          });
        } else {
          ws.reject();
        }
      }
    }).requestHandler(new Handler<HttpServerRequest>() {
      
    public void handle(HttpServerRequest req) {
        if (req.path().equals("/")) req.response().sendFile("com/globex/app/index.html"); // Serve the html
      }
    }).listen(8080);
  }
  
  
  //Create the handler who add the user to the maps and ask to the rest of nodes when the verticle is deployed
  private AsyncResultHandler<String> newVerticleUser(final ServerWebSocket ws, final JsonObject config){
  
  AsyncResultHandler<String> handler = new AsyncResultHandler<String>() {
      public void handle(AsyncResult<String> asyncResult) {
          if (asyncResult.succeeded()) {
              depID.put(config.getString("name"), asyncResult.result()); //Save the deploymentID to later remove the verticle
//              logger.info("ADD : "+config.getString("name")+" WITH DepID: "+depID.get(config.getString("name")));
              
            //A new handler to send the user messages through the websocket
      		Handler<Message<JsonObject>> userHandler = newUserHandler(ws);	                                                                  
      		vertx.eventBus().registerHandler(config.getString("name"), userHandler);
          } else {
              asyncResult.cause().printStackTrace();
          }
      }
      };
      
      return handler;
  }
  
  
  //Create a new message to send through the websocket from the recived through the bus
  private String newMessage(Message<JsonObject> message){
	    ObjectNode msg = mapper.createObjectNode();
  		msg.put("type", "NoSystem");
		msg.put("name", message.body().getString("name"));
		msg.put("color", message.body().getString("color"));
		msg.put("message", message.body().getString("message"));
		return msg.toString();
  }
  
  
  //Create the duplicatedUser message
  private String newMessageDuplicatedUser(){
	  	ObjectNode msg = mapper.createObjectNode();
		msg.put("type", "system");
		msg.put("message", "Ya existe un usuario con ese nombre");
		return msg.toString();
  }
  
  
  //Remove the verticle and unregister the handler
  private void deleteUser (String user, Handler<Message<JsonObject>> handler){ 
//	    logger.info("DELETING: "+ user +" WITH DepID: "+depID.get(user));
	    container.undeployVerticle(depID.get(user));
		colors.remove(user);
		users.remove(user);
		depID.remove(user);
		vertx.eventBus().unregisterHandler(user, handler); 
  }
  
  
  //Create a JSON with the configuration and add the user to the users and colors maps
  private JsonObject newUser (JsonNode message){
	  	JsonObject config = new JsonObject();								 
  		config.putString("name", message.get("user").asText());
		config.putString("chat", message.get("chat").asText());
		users.put(message.get("user").asText(), message.get("chat").asText()); 
		colors.put(message.get("user").asText(), colorsArray[colorIndex]); 
		colorIndex = (int) ((Math.random()*100) % colorsArray.length);
		return config;
  }
  
  
  //Create the handler that sends the user messages through the websocket
  private Handler<Message<JsonObject>> newUserHandler (final ServerWebSocket ws){
	  Handler<Message<JsonObject>> userHandler = new Handler<Message<JsonObject>>() {
      public void handle(Message<JsonObject> message) {
		try{//Try to send the message
			ws.writeTextFrame(newMessage(message));
		}catch(IllegalStateException e){ //The user is offline, so I delete it.
			deleteUser(message.body().getString("sender"),this);
		}
      }
    };  
    return userHandler;
  }

}
