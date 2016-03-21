import java.io.IOException;
import java.util.Map;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.AttributeUpdate;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.model.AttributeAction;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.AttributeValueUpdate;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class LambdaFunctionHandler implements RequestHandler<DynamodbEvent, Object> {

    @Override
    public String handleRequest(DynamodbEvent ddbEvent, Context context) {
    	LambdaLogger logger = context.getLogger();
    	
		AmazonDynamoDBClient dbClient = new AmazonDynamoDBClient();
		dbClient.setRegion(Region.getRegion(Regions.US_WEST_2));
		DynamoDB dynamoDB = new DynamoDB(dbClient);
		
		Table table = dynamoDB.getTable("registry_network_data");
    	
    	for (DynamodbStreamRecord record : ddbEvent.getRecords()){
    		logger.log(record.getEventID() + "|" + record.getEventName() + "|" + record.getDynamodb().toString());
    		if (record.getEventName().equals("INSERT")) {
	    		Map<String, AttributeValue> keyMap = record.getDynamodb().getKeys();
	    		String deviceId = keyMap.get("DeviceId").getS();
	    		String hiveGroupingKey = keyMap.get("HiveGroupingKey").getS();
	    		logger.log(deviceId + " / " + hiveGroupingKey);
	    		Map<String, AttributeValue> newImageMap = record.getDynamodb().getNewImage();
	    		if (newImageMap.get("DefaultGatewayMac")!=null) {
		    		logger.log(newImageMap.get("DefaultGatewayMac").getS());
		    		if (newImageMap.get("DefaultGatewayMac").getS().matches("[0:-]+")!=true) {
		    			ClientResponse response = LambdaFunctionHandler.executeService(newImageMap.get("DefaultGatewayMac").getS());
		    			String output = response.getEntity(String.class);
						logger.log(response.getStatus() + " | " + output);
						
						if (response.getStatus()==200) {
							ObjectMapper m = new ObjectMapper();
							JsonNode rootNode;
							try {
								rootNode = m.readTree(output);
								if (rootNode.get("result").asInt()==200) {
									JsonNode data = rootNode.get("data");
									String lat = data.get("lat").asText();
									String lon = data.get("lon").asText();
									String range = data.get("range").asText();
									LambdaFunctionHandler.updateDynamoDBRecord(deviceId, hiveGroupingKey, lat, lon, range, table);
								}
							} catch (JsonProcessingException e) {
								e.printStackTrace();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
		    		}
	    		}
    		}
    	}
    	dynamoDB.shutdown();
    	logger.log("Successfully processed " + ddbEvent.getRecords().size() + " records.");
    	return "Successfully processed " + ddbEvent.getRecords().size() + " records.";
    }
    
    public static ClientResponse executeService(String macAddress) {
		Client client = Client.create();
		WebResource webResource = client.resource("http://api.mylnikov.org/wifi/main.py/get?bssid=" + macAddress);
		return webResource.type("application/json").get(ClientResponse.class);
    }
    
    public static void updateDynamoDBRecord(String deviceId, String hiveGroupingKey, String lat, String lon, String range, Table t) {
    	UpdateItemOutcome outcome = t.updateItem(new UpdateItemSpec()
    		    .withPrimaryKey("DeviceId", deviceId, "HiveGroupingKey", hiveGroupingKey)
    		    .withAttributeUpdate(
    		        new AttributeUpdate("latitude").put(lat),
    		        new AttributeUpdate("longitude").put(lon),
    		    	new AttributeUpdate("range").put(range)));
    }
}
