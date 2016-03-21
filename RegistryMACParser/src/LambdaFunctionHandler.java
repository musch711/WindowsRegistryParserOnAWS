import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.UUID;

import org.apache.commons.io.IOUtils;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.williballenthin.rejistry.RegistryHiveBuffer;
import com.williballenthin.rejistry.RegistryKey;
import com.williballenthin.rejistry.RegistryParseException;

public class LambdaFunctionHandler implements RequestHandler<S3Event, Object> {
    private static HashMap<String, HashMap<String, String>> results = new HashMap<String, HashMap<String, String>>();
    
    @Override
    public Object handleRequest(S3Event input, Context context) {
        context.getLogger().log("Input: " + input.toJson());
        
        S3EventNotificationRecord record = input.getRecords().get(0);

        String srcBucket = record.getS3().getBucket().getName();
        String srcKey = record.getS3().getObject().getKey().replace('+', ' ');

        try {
			srcKey = URLDecoder.decode(srcKey, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
        
        AmazonS3 s3Client = new AmazonS3Client();
        /*
        ObjectListing listing = s3Client.listObjects(new ListObjectsRequest().withBucketName(srcBucket));
        for (S3ObjectSummary objectSummary : listing.getObjectSummaries()) {
          context.getLogger().log("Key: " + objectSummary.getKey());
        }        
        */
        S3Object s3Object = s3Client.getObject(new GetObjectRequest(srcBucket, srcKey));
        
        InputStream objectData = s3Object.getObjectContent();
	
        try {
			LambdaFunctionHandler.parseSoftwareHive(objectData);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (RegistryParseException e) {
			e.printStackTrace();
		}

		try {
			objectData.close();
			s3Object.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		AmazonDynamoDBClient dbClient = new AmazonDynamoDBClient();
		dbClient.setRegion(Region.getRegion(Regions.US_WEST_2));
		DynamoDB dynamoDB = new DynamoDB(dbClient);
		
		Table table = dynamoDB.getTable("registry_network_data");
		String  hiveGroupingKey = UUID.randomUUID().toString();
		
		for (HashMap.Entry<String, HashMap<String,String>> entry : results.entrySet()) {
			//context.getLogger().log(entry.getKey() + "/" + entry.getValue().get("ProfileName") + "/" + entry.getValue().get("DefaultGatewayMac"));
			Item item = new Item();
			item.withPrimaryKey("DeviceId", entry.getKey());
			item.withString("HiveGroupingKey", hiveGroupingKey);
			item.withString("ProfileName", entry.getValue().get("ProfileName"));
			item.withString("DefaultGatewayMac", entry.getValue().get("DefaultGatewayMac"));
			item.withString("DateCreated", entry.getValue().get("DateCreated"));
			item.withString("DateLastConnected", entry.getValue().get("DateLastConnected"));
			table.putItem(item);
		}
		
		dynamoDB.shutdown();
        return null;
    }
    
    public static void parseSoftwareHive(InputStream s) throws IOException, RegistryParseException {
		byte[] bytes =  IOUtils.toByteArray(s);

		ByteBuffer b = ByteBuffer.wrap(bytes);
		RegistryHiveBuffer hive = new RegistryHiveBuffer(b);
		
		RegistryKey networkList = hive.getRoot().getSubkey("Microsoft").getSubkey("Windows NT").getSubkey("CurrentVersion").getSubkey("NetworkList");
		RegistryKey profiles = networkList.getSubkey("Profiles");
		RegistryKey signatures = networkList.getSubkey("Signatures");
		for (RegistryKey p: profiles.getSubkeyList()) {
			HashMap<String,String> temp = new HashMap<String,String>();
			temp.put("ProfileName", p.getValue("ProfileName").getValue().getAsString());
			temp.put("DateCreated", LambdaFunctionHandler.convertFromBinaryToDate(p.getValue("DateCreated").getValue().getAsRawData()));
			temp.put("DateLastConnected", LambdaFunctionHandler.convertFromBinaryToDate(p.getValue("DateLastConnected").getValue().getAsRawData()));
			results.put(p.getName(), temp);
		}
		
		for (RegistryKey p: signatures.getSubkey("Managed").getSubkeyList()) {
			String mac = LambdaFunctionHandler.convertFromBinaryToMacAddress(p.getValue("DefaultGatewayMac").getValue().getAsRawData());			
			results.get(p.getValue("ProfileGuid").getValue().getAsString()).put("DefaultGatewayMac", mac);
		}

		for (RegistryKey p: signatures.getSubkey("Unmanaged").getSubkeyList()) {
			String mac = LambdaFunctionHandler.convertFromBinaryToMacAddress(p.getValue("DefaultGatewayMac").getValue().getAsRawData());
			results.get(p.getValue("ProfileGuid").getValue().getAsString()).put("DefaultGatewayMac", mac);
		}    	
    }
    
	public static String convertFromBinaryToMacAddress(ByteBuffer buffer) {
		String mac = "";
		while (buffer.hasRemaining()) {
			String temp = Integer.toHexString(buffer.get());
			temp = LambdaFunctionHandler.shortenBinary(temp);
			mac = mac + temp + "-";
		}
		mac = mac.substring(0,mac.length()-1);
		return mac;
	}
	
	public static String convertFromBinaryToDate(ByteBuffer buffer) {
		String y1 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		String y2 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		Integer year = Integer.valueOf(y2 + y1, 16);
		
		String m = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		Integer month = Integer.valueOf(m, 16);
		
		//Skip dayofWeek bytes
		buffer.get();
		buffer.get();
		
		String dom1 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		String dom2 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		Integer dayOfMonth = Integer.valueOf(dom1 + dom2, 16);
		
		String h1 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		String h2 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		Integer hours = Integer.valueOf(h1 + h2, 16);

		String m1 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		String m2 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		Integer minutes = Integer.valueOf(m1 + m2, 16);
		
		//String s1 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		//String s2 = LambdaFunctionHandler.shortenBinary(Integer.toHexString(buffer.get()));
		//Integer seconds = Integer.valueOf(s1 + s2, 16);		
		
		return month + "/" + dayOfMonth + "/" + year + " " + hours + ":" + minutes;
	}
	
	public static String shortenBinary(String s) {
		if (s.length() > 2) {
			s = s.substring(s.length()-2);
		} else if (s.length() == 1) {
			s = "0" + s;
		}
		return s;
	}	
}
