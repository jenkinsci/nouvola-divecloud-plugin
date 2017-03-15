package org.jenkinsci.plugins.nouvoladivecloud;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;
import net.sf.json.JSONException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;

import java.io.*;
import java.net.*;
import java.util.ArrayList;

/**
 * Adds the ability to run a Nouvola DiveCloud test plan as a build step.
 *
 * @author Shawn MacArthur
 */
public class NouvolaBuilder extends Builder {

    private final String planID;
    private final String apiKey;
    private final Secret credsPass;
    private final String pollInterval;
    private final String returnURL;
    private final String listenTimeOut;

    @DataBoundConstructor
    public NouvolaBuilder(String planID,
                          String apiKey,
                          String credsPass,
                          String pollInterval,
                          String returnURL,
                          String listenTimeOut) {
        this.planID = planID;
        this.apiKey = apiKey;
        this.credsPass = Secret.fromString(credsPass);
        this.pollInterval = pollInterval;
        this.returnURL = returnURL;
        this.listenTimeOut = listenTimeOut;
    }

    public String getPlanID() {
        return planID;
    }

    public String getApiKey() {
        return apiKey;
    }

    public Secret getCredsPass() {
        return credsPass;
    }

    public String getPollInterval(){
        return pollInterval;
    }

    public String getReturnURL() {
        return returnURL;
    }

    public String getListenTimeOut() {
	return listenTimeOut;
    }

    /**
     * Object for process status and messages
     */
    private class ProcessStatus{
        public boolean pass;
        public String message;

        public ProcessStatus(boolean pass, String message){
            this.pass = pass;
            this.message = message;
        }
    }

    /**
     * Send HTTP request and return a process status
     */
    private ProcessStatus sendHTTPRequest(String url,
                                          String httpAction,
                                          String apiKey,
                                          String data){
        ProcessStatus status = new ProcessStatus(true, "");
        try{
            URL urlObj = new URL(url);
            try{
                HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
                conn.setRequestMethod(httpAction);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("charset", "utf-8");
                conn.setRequestProperty("x-api", apiKey);
                
                if(data != null){
                    OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), "UTF-8");
                    writer.write(data);
                    writer.flush();
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                String result = "";

                while ((line = reader.readLine()) != null){
                    result = result + line;

                }
                reader.close();
                if(!result.isEmpty()){
                    status.message = result;
                }
                else{
                    status.pass = false;
                    status.message = "Nouvola DiveCloud API " + url + " did not return a result";
                }
            }
            catch(IOException ex){
                status.pass = false;
                status.message = ex.toString();
            }
        }
        catch(MalformedURLException ex){
            status.pass = false;
            status.message = ex.toString();
        }
        return status;
    }

    /**
     * Process JSON Strings returned by requests to Nouvola API
     * test_id is the only one that is an int so we will check for it
     */
    private ProcessStatus parseJSONString(String jsonString, String key){
        ProcessStatus status = new ProcessStatus(true, "");
        try{
            JSONObject jObj = JSONObject.fromObject(jsonString);
            if(key.equals("test_id")){
                status.message = Integer.toString(jObj.getInt(key));
            }
            else{
                status.message = jObj.getString(key);
            }
        }
        catch(JSONException ex){
            status.pass = false;
            status.message = ex.toString();
        }
        return status;
    }

    /**
     * Write to a file
     * Return an error message if failed else return an empty string
     */
    private String writeToFile(String filename, String content){
        String result = "";
        try{
            Writer writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(filename), "UTF-8"));
            writer.write(content);
            writer.close();
        }
        catch(IOException ex){
            result = ex.toString();
        }
        return result;
    }

    /**
     *
     * 1. Send a POST request to register a return URL to Nouvola
          Divecloud's webhook API for a run event.
       2. Send a POST request to the Nouvola DiveCloud API
          version 1 with the specified plan ID, API key, and
          encryption key (if used).
       3. Listen for a response from the API and once received
          Stop and return the result.
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        ProcessStatus status;
        boolean isWebhook = false;
        String urlParameters = "creds_pass=" + Secret.toString(credsPass);
        String registerUrl   = "https://divecloud.nouvola.com/api/v1/hooks";
        String triggerUrl = "https://divecloud.nouvola.com/api/v1/plans/" + planID + "/run";
        String pollUrl = "https://divecloud.nouvola.com/api/v1/test_instances/";
        String testId = "";
        String retURL = "";
        int listenPort = -1;
        String results_file = "results.txt";

        // checks for return URL
        listener.getLogger().println("Checking return URL...");
        if (!returnURL.isEmpty()){
            try{
                URL url = new URL(returnURL);
                listenPort = url.getPort();
                if( listenPort == -1) listenPort = 9999; //default to this if there is no port in the url
                String protocol = url.getProtocol();
                String host = url.getHost();
                String path = url.getPath();
                retURL = protocol + "://" + host + ":" + listenPort + path;
                listener.getLogger().println("Return URL OK");
                isWebhook = true;
	        }
            catch(MalformedURLException ex){
	            listener.getLogger().println("The return URL given is invalid. Polling Divecloud instead.");
            }
        }

        // Register the return URL with the webhook service
        if (isWebhook){
            listener.getLogger().println("Registering URL: " + retURL);
            JSONObject registerData = new JSONObject();
            registerData.put("event", "run_plan");
            registerData.put("resource_id", planID);
            registerData.put("url", retURL);
            listener.getLogger().println("Connecting to..." + registerUrl);
            status = sendHTTPRequest(registerUrl, "POST", apiKey, registerData.toString());
            if(!status.pass){
                listener.getLogger().println("Registration failed: " + status.message);
                return status.pass;
            }
        }

        // Trigger a plan run
        listener.getLogger().println("Triggering: " + triggerUrl);
        status = sendHTTPRequest(triggerUrl, "POST", apiKey, null);
        if(!status.pass){
            listener.getLogger().println("Triggering testplan failed: " + status.message);
            return status.pass;
        }
        status = parseJSONString(status.message, "test_id");
        if(!status.pass){
            listener.getLogger().println("Could not get a test ID: " + status.message);
            return status.pass;
        }
        testId = status.message;

        // listen for results
        String jsonMsg = "";
        if (isWebhook){
            try{
                boolean posted = false;
                ServerSocket server = new ServerSocket(listenPort);
		        int timeout = 60; //timeout default is 60 minutes
		        if(!listenTimeOut.isEmpty())
		            timeout = Integer.parseInt(listenTimeOut);
		        server.setSoTimeout(timeout * 60000);
                listener.getLogger().println("Listening on port " + listenPort + "...");
                while(!posted){
                    Socket socket = server.accept(); //accept requests
                    BufferedReader clientSent = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    BufferedWriter clientResp = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
                    String line = clientSent.readLine();
	 	            int contLength = 0;
                    if(line != null && line.contains("POST")){
                        while(!line.isEmpty()){
                            listener.getLogger().println(line);
                            if(line.contains("Content-Length")){
                                contLength = Integer.parseInt(line.substring(16));
                            }
                            line = clientSent.readLine();
			                if(line == null){
				                line = "";
			                }
                        }
                        // Once a line doesn't exist, read the rest of the message
                        int bufChar = 0;
                        while(contLength > 0){
                            bufChar = clientSent.read();
                            char msgChar = (char) bufChar;
                            jsonMsg = jsonMsg.concat(String.valueOf(msgChar));
                            contLength = contLength - 1;
                        }
                        listener.getLogger().println(jsonMsg);
                        clientResp.write("HTTP/1.1 200 OK\r\n\r\n" + "Accepted");
                        posted = true;
                    }
                    else{
                        clientResp.write("HTTP/1.1 200 OK\r\n\r\n" + "Accepts POST requests only");
                    }
		    
                    clientResp.close();
                    clientSent.close();
                    socket.close();
                }
		        if(server != null){
		            server.close();
		        }
            }
	        catch(SocketTimeoutException ex){
		        listener.getLogger().println("No callback received - timing out. Please check on your test at Nouvola Divecloud");
	        }
            catch(IOException ex){
                listener.getLogger().println("Socket server error: " + ex);
                status.pass = false;
            }
        }
        else{
            // no webhook means poll for results
            boolean finished = false;
            listener.getLogger().println("Polling for results at: " + pollUrl + testId);
            while(!finished){
                status = sendHTTPRequest(pollUrl + testId, "GET", apiKey, null);
                if(!status.pass){
                    listener.getLogger().println(status.message);
                    return status.pass;
                }
                jsonMsg = status.message;
                status = parseJSONString(jsonMsg, "status");
                if(!status.pass){
                    listener.getLogger().println(status.message);
                    return status.pass;
                }
                if(status.message.equals("Emailed")) finished = true;
                else{
                    int interval = 30; //default to 30 seconds
                    if(!pollInterval.isEmpty()) interval = Integer.parseInt(pollInterval);
                    try{
                        Thread.sleep(interval * 1000);
                    }
                    catch(InterruptedException ex){
                        listener.getLogger().println("Polling interrupted. Check test status at Nouvola DiveCloud");
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }

        }

        
        if(!jsonMsg.isEmpty()){
            status = parseJSONString(jsonMsg, "outcome");
            if(status.pass && status.message.equals("Pass")){
                listener.getLogger().println("DiveCloud test passed");

                // create artifact
                String path = build.getProject().getWorkspace().toString() + "/" + results_file;
                String writeStatus = writeToFile(path, jsonMsg);
                if(!writeStatus.isEmpty()){
                    status.pass = false;
                    status.message = "Failed to create artifact: " + writeStatus;
                    listener.getLogger().println(status.message);
                }
                listener.getLogger().println("Report ready");
            }
            else{
                listener.getLogger().println("DiveCloud test failed: " + status.message);
            }
        }
        else{
            status.pass = false;
            status.message = "DiveCloud test did not return anything - empty JSON message";
            listener.getLogger().println(status.message);
        }

        return status.pass;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckPlanID(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a Plan ID");
            return FormValidation.ok();
        }

        public FormValidation doCheckApiKey(@QueryParameter String value) throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please enter your Nouvola API Key");
            return FormValidation.ok();
        }

	public FormValidation doCheckListenTimeOut(@QueryParameter String value) throws IOException, ServletException {
	    try{
	        Integer.parseInt(value);
		return FormValidation.ok();
	    }
	    catch(NumberFormatException ex){
		return FormValidation.error("Please enter an integer");
	    }
	}

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public String getDisplayName() {
            return "Run Nouvola DiveCloud Test";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            save();
            return super.configure(req,formData);
        }

    }
}
