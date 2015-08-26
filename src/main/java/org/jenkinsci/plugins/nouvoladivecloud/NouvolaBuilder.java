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
    
    @DataBoundConstructor
    public NouvolaBuilder(String planID, String apiKey, String credsPass) {
        this.planID = planID;
        this.apiKey = apiKey;
        this.credsPass = Secret.fromString(credsPass);
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
    
    
    /**
     * Sends a POST request to the Nouvola DiveCloud API version 1 with the specified plan ID, API key, and encryption key (if used). 
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
       System.out.println("Performing...");

		String urlParameters = "creds_pass=" + Secret.toString(credsPass);
		byte[] postData       = urlParameters.getBytes( StandardCharsets.UTF_8 );
		int    postDataLength = postData.length;
		String request        = "https://divecloud.nouvola.com/api/v1/plans/" + planID + "/run";

		try{
			URL url = new URL(request);
			System.out.println("Connecting to..." + request);
			
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
				    System.out.println(line);
				}

				writer.close();
				
				reader.close();  
              }
              catch(IOException ex){
				System.out.println(ex);
			}
  
		}
		catch(MalformedURLException ex){
			System.out.println(ex);
		}

        return true;
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
