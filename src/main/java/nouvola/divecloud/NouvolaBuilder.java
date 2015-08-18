package nouvola.divecloud;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
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
 * Runs a Nouvola DiveCloud test plan.
 *
 * @author Shawn MacArthur
 */
public class NouvolaBuilder extends Builder {

    private final String planID;
    private final String apiKey;
    private final String credsPass;
    

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public NouvolaBuilder(String planID, String apiKey, String credsPass) {
        this.planID = planID;
        this.apiKey = apiKey;
        this.credsPass = credsPass;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     */
    public String getPlanID() {
        return planID;
    }

    public String getApiKey() {
        return apiKey;
    }
    
    public String getCredsPass() {
        return credsPass;
    }
    
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
       System.out.println("Performing...");

		String urlParameters = "creds_pass=" + credsPass;
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

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */
        private boolean useFrench;

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckPlanID(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a Plan ID");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Run Nouvola DiveCloud Test";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            useFrench = formData.getBoolean("useFrench");
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

    }
}

