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
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
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
    private final String returnURL;

    @DataBoundConstructor
    public NouvolaBuilder(String planID, String apiKey, String credsPass, String returnURL) {
        this.planID = planID;
        this.apiKey = apiKey;
        this.credsPass = Secret.fromString(credsPass);
        this.returnURL = returnURL;
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

    public String getReturnURL() {
        return returnURL;
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
        boolean pass = true;
        listener.getLogger().println("Performing...");

        String urlParameters = "creds_pass=" + Secret.toString(credsPass);

        String registerUrl   = "http://preproduction.nouvola.com/api/v1/hooks";
        String triggerUrl = "http://preproduction.nouvola.com/api/v1/plans/" + planID + "/run";

        listener.getLogger().println(returnURL);

        if (!returnURL.isEmpty()){

            // Register the return URL with the webhook service
            JSONObject registerData = new JSONObject();
            registerData.put("event", "run_plan");
            registerData.put("resource_id", planID);
            registerData.put("url", returnURL);

            try{
                URL url = new URL(registerUrl);
                listener.getLogger().println("Connecting to..." + registerUrl);

                try{
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty( "Content-Type", "application/json");
                    conn.setRequestProperty( "charset", "utf-8");
                    conn.setRequestProperty( "x-api", apiKey);

                    OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());

                    writer.write(registerData.toString());
                    writer.flush();

                    String line;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));

                    while ((line = reader.readLine()) != null) {
                        listener.getLogger().println(line);
                        // parse JSON here
                    }

                    writer.close();

                    reader.close();
                }
                catch(IOException ex){
                    listener.getLogger().println(ex);
                }

            }
            catch(MalformedURLException ex){
                listener.getLogger().println(ex);
            }
        }

        // Trigger a plan run
        try{
            URL url = new URL(triggerUrl);
            listener.getLogger().println("Connecting to..." + triggerUrl);

            try{
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty( "Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty( "charset", "utf-8");
                conn.setRequestProperty( "x-api", apiKey);


                OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());

                writer.write(urlParameters);
                writer.flush();

                String line;
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));

                while ((line = reader.readLine()) != null) {
                    listener.getLogger().println(line);
                    // parse JSON here
                }

                writer.close();

                reader.close();
              }
              catch(IOException ex){
                listener.getLogger().println(ex);
                pass = false;
            }

        }
        catch(MalformedURLException ex){
            listener.getLogger().println(ex);
            pass = false;
        }

        // listen for a callback
        if (!returnURL.isEmpty()){
            try{
                boolean posted = false;
                ServerSocket server = new ServerSocket(9999);
                listener.getLogger().println("Listening on port 9999");
                while(!posted){
                    Socket socket = server.accept(); //accept requests
                    BufferedReader clientSent = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    BufferedWriter clientResp = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                    String line = clientSent.readLine();
                    int contLength = 0;
                    if(line.contains("POST")){
                        while(!line.isEmpty()){
                            listener.getLogger().println(line);
                            if(line.contains("Content-Length")){
                                contLength = Integer.parseInt(line.substring(16));
                            }
                            line = clientSent.readLine();
                        }
                        // Once a line doesn't exist, read the rest of the message
                        String jsonMsg = "";
                        int bufChar = 0;
                        while(contLength > 0){
                            bufChar = clientSent.read();
                            char msgChar = (char) bufChar;
                            jsonMsg = jsonMsg + msgChar;
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
            catch(IOException ex){
                listener.getLogger().println("Socket server error: " + ex);
                pass = false;
            }

        }

        return pass;
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
