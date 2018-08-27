package org.jenkinsci.plugins.ParameterizedRemoteTrigger.utils;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.trimToNull;
import static org.jenkinsci.plugins.ParameterizedRemoteTrigger.utils.StringTools.NL;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Writer;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import hudson.FilePath;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.BuildContext;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.ConnectionResponse;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.JenkinsCrumb;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.RemoteJenkinsServer;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.auth2.Auth2;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.auth2.NullAuth;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.exceptions.ExceedRetryLimitException;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.exceptions.ForbiddenException;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.exceptions.UnauthorizedException;
import org.jenkinsci.plugins.ParameterizedRemoteTrigger.exceptions.UrlNotFoundException;

import hudson.AbortException;
import hudson.ProxyConfiguration;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import net.sf.json.util.JSONUtils;

public class HttpHelper {

	private static final String paramerizedBuildUrl = "/buildWithParameters";
	private static final String normalBuildUrl = "/build";
	private static final String buildTokenRootUrl = "/buildByToken";
	public static final String HTTP_GET = "GET";
	public static final String HTTP_POST = "POST";

	private static Logger logger = Logger.getLogger(HttpHelper.class.getName());

	/**
	 * Helper function to allow values to be added to the query string from any
	 * method.
	 *
	 * @param item
	 */
	private static String addToQueryString(String queryString, String item) {
		if (isBlank(queryString)) {
			return item;
		} else {
			return queryString + "&" + item;
		}
	}

	/**
	 * Return the Collection<String> in an encoded query-string.
	 *
	 * @param parameters
	 *            the parameters needed to trigger the remote job.
	 * @return query-parameter-formated URL-encoded string.
	 */
	private static String buildUrlQueryString(Collection<String> parameters) {

		// List to hold the encoded parameters
		List<String> encodedParameters = new ArrayList<String>();

		if (parameters != null) {
			for (String parameter : parameters) {

				// Step #1 - break apart the parameter-pairs (because we don't want to encode
				// the "=" character)
				String[] splitParameters = parameter.split("=");

				// List to hold each individually encoded parameter item
				List<String> encodedItems = new ArrayList<String>();
				for (String item : splitParameters) {
					try {
						// Step #2 - encode each individual parameter item add the encoded item to its
						// corresponding list

						encodedItems.add(encodeValue(item));

					} catch (Exception e) {
						// do nothing
						// because we are "hard-coding" the encoding type, there is a 0% chance that
						// this will fail.
						logger.warning(e.toString());
					}

				}

				// Step #3 - reunite the previously separated parameter items and add them to
				// the corresponding list
				encodedParameters.add(StringUtils.join(encodedItems, "="));
			}
		}
		return StringUtils.join(encodedParameters, "&");
	}

	/**
	 * Same as above, but takes in to consideration if the remote server has any
	 * default parameters set or not
	 * 
	 * @param isRemoteJobParameterized
	 *            Boolean indicating if the remote job is parameterized or not
	 * @return A string which represents a portion of the build URL
	 */
	private static String getBuildTypeUrl(boolean isRemoteJobParameterized, Collection<String> params) {
		boolean isParameterized = false;

		if (isRemoteJobParameterized || (params != null && params.size() > 0)) {
			isParameterized = true;
		}

		if (isParameterized) {
			return paramerizedBuildUrl;
		} else {
			return normalBuildUrl;
		}
	}

	protected static String generateJobUrl(RemoteJenkinsServer remoteServer, String jobNameOrUrl)
			throws AbortException {
		if (isEmpty(jobNameOrUrl))
			throw new IllegalArgumentException("Invalid job name/url: " + jobNameOrUrl);
		String remoteJobUrl;
		String _jobNameOrUrl = jobNameOrUrl.trim();
		if (FormValidationUtils.isURL(_jobNameOrUrl)) {
			remoteJobUrl = _jobNameOrUrl;
		} else {
			remoteJobUrl = remoteServer.getAddress();
			if (remoteJobUrl == null) {
				throw new AbortException(
						"The remote server address can not be empty, or it must be overridden on the job configuration.");
			}
			while (remoteJobUrl.endsWith("/"))
				remoteJobUrl = remoteJobUrl.substring(0, remoteJobUrl.length() - 1);

			String[] split = _jobNameOrUrl.trim().split("/");
			for (String segment : split) {
				remoteJobUrl = String.format("%s/job/%s", remoteJobUrl, encodeValue(segment));
			}
		}
		return remoteJobUrl;
	}

	/**
	 * Helper function for character encoding
	 *
	 * @param dirtyValue
	 *            something that wasn't encoded in UTF-8
	 * @return encoded value
	 */
	public static String encodeValue(String dirtyValue) {
		String cleanValue = "";

		try {
			cleanValue = URLEncoder.encode(dirtyValue, "UTF-8").replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		return cleanValue;
	}

	private static String readInputStream(HttpURLConnection connection) throws IOException {
		BufferedReader rd = null;
		try {

			InputStream is;
			try {
				is = connection.getInputStream();
			} catch (FileNotFoundException e) {
				// In case of a e.g. 404 status
				is = connection.getErrorStream();
			}

			rd = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			String line;
			StringBuilder response = new StringBuilder();
			while ((line = rd.readLine()) != null) {
				if (response.length() > 0)
					response.append(NL);
				response.append(line);
			}
			return response.toString();

		} finally {
			closeQuietly(rd);
		}
	}

	/**
	 * Tries to obtain a Jenkins Crumb from the remote Jenkins server.
	 *
	 * @param context
	 *            the context of this Builder/BuildStep.
	 * @return {@link JenkinsCrumb} a JenkinsCrumb.
	 * @throws IOException
	 *             if the request failed.
	 */
	@Nonnull
	private static JenkinsCrumb getCrumb(BuildContext context, Auth2 overrideAuth, boolean isCacheEnabled)
			throws IOException {
		String address = context.effectiveRemoteServer.getAddress();
		if (address == null) {
			throw new AbortException(
					"The remote server address can not be empty, or it must be overridden on the job configuration.");
		}

		URL crumbProviderUrl;
		String globalHost = "";
		try {
			String xpathValue = URLEncoder.encode("concat(//crumbRequestField,\":\",//crumb)", "UTF-8");
			crumbProviderUrl = new URL(address.concat("/crumbIssuer/api/xml?xpath=").concat(xpathValue));
			globalHost = crumbProviderUrl.getHost();

			JenkinsCrumb jenkinsCrumb = DropCachePeriodicWork.safeGetCrumb(globalHost, isCacheEnabled);
			if (jenkinsCrumb != null) {
				context.logger.println("reuse cached crumb: " + globalHost);
				return jenkinsCrumb;
			}
			HttpURLConnection connection = getAuthorizedConnection(context, crumbProviderUrl, overrideAuth);
			int responseCode = connection.getResponseCode();
			if (responseCode == 401) {
				throw new UnauthorizedException(crumbProviderUrl);
			} else if (responseCode == 403) {
				throw new ForbiddenException(crumbProviderUrl);
			} else if (responseCode == 404) {
				context.logger.println("CSRF protection is disabled on the remote server.");
				return DropCachePeriodicWork.safePutCrumb(globalHost, new JenkinsCrumb(), isCacheEnabled);
			} else if (responseCode == 200) {
				context.logger.println("CSRF protection is enabled on the remote server.");
				String response = readInputStream(connection);
				String[] split = response.split(":");
				JenkinsCrumb crumb = new JenkinsCrumb(split[0], split[1]);
				return DropCachePeriodicWork.safePutCrumb(globalHost, crumb, isCacheEnabled);
			} else {
				throw new RuntimeException(String.format("Unexpected response. Response code: %s. Response message: %s",
						responseCode, connection.getResponseMessage()));
			}
		} catch (FileNotFoundException e) {
			context.logger.println("CSRF protection is disabled on the remote server.");
			return DropCachePeriodicWork.safePutCrumb(globalHost, new JenkinsCrumb(), isCacheEnabled);
		}
	}

	/**
	 * For POST requests a crumb is needed. This methods gets a crumb and sets it in
	 * the header.
	 * https://wiki.jenkins.io/display/JENKINS/Remote+access+API#RemoteaccessAPI-CSRFProtection
	 *
	 * @param connection
	 * @param context
	 * @throws IOException
	 */
	private static void addCrumbToConnection(HttpURLConnection connection, BuildContext context, Auth2 overrideAuth,
			boolean isCacheEnabled) throws IOException {
		String method = connection.getRequestMethod();
		if (method != null && method.equalsIgnoreCase("POST")) {
			JenkinsCrumb crumb = getCrumb(context, overrideAuth, isCacheEnabled);
			if (crumb.isEnabledOnRemote()) {
				connection.setRequestProperty(crumb.getHeaderId(), crumb.getCrumbValue());
			}
		}
	}

	private static HttpURLConnection getAuthorizedConnection(BuildContext context, URL url, Auth2 overrideAuth)
			throws IOException {
		URLConnection connection = context.effectiveRemoteServer.isUseProxy() ? ProxyConfiguration.open(url)
				: url.openConnection();

		Auth2 serverAuth = context.effectiveRemoteServer.getAuth2();

		if (overrideAuth != null && !(overrideAuth instanceof NullAuth)) {
			// Override Authorization Header if configured locally
			overrideAuth.setAuthorizationHeader(connection, context);
		} else if (serverAuth != null) {
			// Set Authorization Header configured globally for remoteServer
			serverAuth.setAuthorizationHeader(connection, context);
		}

		return (HttpURLConnection) connection;
	}

	private static String getUrlWithoutParameters(String url) {
		String result = url;
		try {
			URI uri = new URI(url);
			result = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, uri.getFragment()).toString();
		} catch (URISyntaxException e) {
			logger.log(Level.WARNING, e.getMessage(), e);
		}
		return result;
	}

	/**
	 * Build the proper URL to trigger the remote build
	 *
	 * All passed in string have already had their tokens replaced with real values.
	 * All 'params' also have the proper character encoding
	 *
	 * @param jobNameOrUrl
	 *            Name of the remote job
	 * @param securityToken
	 *            Security token used to trigger remote job
	 * @param params
	 *            Parameters for the remote job
	 * @param isRemoteJobParameterized
	 *            Is the remote job parameterized
	 * @param context
	 *            The build context used in this plugin
	 * @return fully formed, fully qualified remote trigger URL
	 * @throws IOException
	 *             throw when it can't pass data checking
	 */
	public static String buildTriggerUrl(String jobNameOrUrl, String securityToken, Collection<String> params,
			boolean isRemoteJobParameterized, BuildContext context) throws IOException {

		String triggerUrlString;
		String query = "";

		if (context.effectiveRemoteServer.getHasBuildTokenRootSupport()) {
			// start building the proper URL based on known capabiltiies of the remote
			// server
			if (context.effectiveRemoteServer.getAddress() == null) {
				throw new AbortException(
						"The remote server address can not be empty, or it must be overridden on the job configuration.");
			}
			triggerUrlString = context.effectiveRemoteServer.getAddress();
			triggerUrlString += buildTokenRootUrl;
			triggerUrlString += getBuildTypeUrl(isRemoteJobParameterized, params);
			query = addToQueryString(query, "job=" + encodeValue(jobNameOrUrl)); // TODO: does it work with full URL?

		} else {
			triggerUrlString = generateJobUrl(context.effectiveRemoteServer, jobNameOrUrl);
			triggerUrlString += getBuildTypeUrl(isRemoteJobParameterized, params);
		}

		// don't try to include a security token in the URL if none is provided
		if (!securityToken.equals("")) {
			query = addToQueryString(query, "token=" + encodeValue(securityToken));
		}

		// turn our Collection into a query string
		String buildParams = buildUrlQueryString(params);

		if (!buildParams.isEmpty()) {
			query = addToQueryString(query, buildParams);
		}

		// by adding "delay=0", this will (theoretically) force this job to the top of
		// the remote queue
		query = addToQueryString(query, "delay=0");

		triggerUrlString += "?" + query;

		return triggerUrlString;
	}

	private static boolean shouldSendByFormData(Map<String, Object> params) {
		for (String key : params.keySet()) {
			if (params.get(key) instanceof FilePath) {
				return true;
			}
		}

		return false;
	}

	private static String createBoundary() {
		return "===" + System.currentTimeMillis() + "===";
	}

	private static byte[] buildFormData(Map<String, Object> params, String boundary) throws IOException, InterruptedException {
		OutputStream stream = new ByteArrayOutputStream();
		Writer writer = new PrintWriter(stream);
		final String LINE_FEED = "\r\n";

		for (String key : params.keySet()) {
			writer.append("--").append(boundary).append(LINE_FEED);
			writer.append("Content-Disposition: form-data; name=\"").append(key).append("\"");
			Object value = params.get(key);
			if (value instanceof String) {
				writer.append(LINE_FEED);
				writer.append("Content-Type: text/plain; charset=utf-8").append(LINE_FEED);
				writer.append(LINE_FEED);
				writer.append((String) value);
			}
			else {
				FilePath path = (FilePath) value;
				String filename = path.getName();
				writer.append("; filename=\"").append(filename ).append("\"").append(LINE_FEED);
				writer.append("Content-Type: ").append(URLConnection.guessContentTypeFromName(filename)).append(LINE_FEED);
				writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);
				writer.append(LINE_FEED);
				writer.flush();

				InputStream inputStream = path.read();
				byte[] buffer = new byte[4096];
				int bytesRead;
				while ((bytesRead = inputStream.read(buffer)) != -1) {
					stream.write(buffer, 0, bytesRead);
				}
				stream.flush();
				inputStream.close();
			}
			writer.append(LINE_FEED);
			writer.flush();
		}

		writer.append("--").append(boundary).append("--").append(LINE_FEED);
		writer.close();
		return ((ByteArrayOutputStream) stream).toByteArray();
	}
	
	/**
	 * Same as sendHTTPCall, but keeps track of the number of failed connection
	 * attempts (aka: the number of times this method has been called). In the case
	 * of a failed connection, the method calls it self recursively and increments
	 * the number of attempts.
	 *
	 * @see sendHTTPCall
	 * @param urlString
	 *            the URL that needs to be called.
	 * @param requestType
	 *            the type of request (GET, POST, etc).
	 * @param context
	 *            the context of this Builder/BuildStep.
	 * @param postParams
	 *            parameters to post
	 * @param numberOfAttempts
	 *            number of time that the connection has been attempted
	 * @param pollInterval
	 *            interval between each retry in second
	 * @param retryLimit
	 *            the retry uplimit
	 * @param overrideAuth
	 *            auth used to overwrite the default auth
	 * @param rawRespRef
	 *            the raw http response
	 * @return {@link ConnectionResponse} the response to the HTTP request.
	 * @throws IOException
	 *             all the possibilities of HTTP exceptions
	 * @throws InterruptedException
	 *             if any thread has interrupted the current thread.
	 * 
	 */
	private static ConnectionResponse sendHTTPCall(String urlString, String requestType, BuildContext context,
			Map<String, Object> postParams, int numberOfAttempts, int pollInterval, int retryLimit, Auth2 overrideAuth,
			StringBuilder rawRespRef, boolean isCrubmCacheEnabled) throws IOException, InterruptedException {

		JSONObject responseObject = null;
		Map<String, List<String>> responseHeader = null;
		int responseCode = 0;

		byte[] postDataBytes = new byte[] {};
		String parmsString = "";
		boolean postMode = HTTP_POST.equalsIgnoreCase(requestType);
		boolean postWithForm = false;
		String postContentsType = "";
		if (postMode && postParams != null && postParams.size() > 0) {
			postWithForm = shouldSendByFormData(postParams);
			if (postWithForm) {
				String formBoundary = createBoundary();
				postContentsType = "multipart/form-data; boundary=" + formBoundary;
				postDataBytes = buildFormData(postParams, formBoundary);
			}
			else {
				postContentsType = "application/x-www-form-urlencoded";

				List<String> paramList = new ArrayList<String>();
				for (String key : postParams.keySet()) {
					paramList.add(String.format("{0}={1}", key, postParams.get(key)));
				}
				parmsString = buildUrlQueryString(paramList);
				postDataBytes = parmsString.getBytes("UTF-8");
			}
		}

		URL url = new URL(urlString);
		HttpURLConnection conn = getAuthorizedConnection(context, url, overrideAuth);

		try {
			conn.setDoInput(true);
			conn.setRequestProperty("Accept", "application/json");
			conn.setRequestProperty("Accept-Language", "UTF-8");
			conn.setRequestMethod(requestType);
			addCrumbToConnection(conn, context, overrideAuth, isCrubmCacheEnabled);
			// wait up to 5 seconds for the connection to be open
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(10000);
			if (postMode) {
				conn.setRequestProperty("Content-Type", postContentsType);
				conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
				conn.setDoOutput(true);
				conn.getOutputStream().write(postDataBytes);
			}

			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

			logger.finer(String.format("%s begin: %s", urlString, sdf.format(new Date())));
			Instant before = Instant.now();

			conn.connect();

			Instant after = Instant.now();
			logger.finer(
					String.format("%s end: elapsed [%s] ms", urlString, Duration.between(before, after).toMillis()));

			responseHeader = conn.getHeaderFields();
			responseCode = conn.getResponseCode();

			if (responseCode == 401) {
				throw new UnauthorizedException(url);
			} else if (responseCode == 403) {
				throw new ForbiddenException(url);
			} else if (responseCode == 404) {
				throw new UrlNotFoundException(url);
			} else {
				String response = trimToNull(readInputStream(conn));
				if (rawRespRef != null) {
					rawRespRef.append(response);
				}

				// JSONSerializer serializer = new JSONSerializer();
				// need to parse the data we get back into struct
				// listener.getLogger().println("Called URL: '" + urlString + "', got response:
				// '" + response.toString() + "'");

				// Solving issue reported in this comment:
				// https://github.com/jenkinsci/parameterized-remote-trigger-plugin/pull/3#issuecomment-39369194
				// Seems like in Jenkins version 1.547, when using "/build" (job API for
				// non-parameterized jobs), it returns a string indicating the status.
				// But in newer versions of Jenkins, it just returns an empty response.
				// So we need to compensate and check for both.
				if (responseCode >= 400 || JSONUtils.mayBeJSON(response) == false) {
					return new ConnectionResponse(responseHeader, responseCode);
				} else {
					responseObject = (JSONObject) JSONSerializer.toJSON(response);
				}
			}

		} catch (IOException e) {

			// E.g. "HTTP/1.1 403 No valid crumb was included in the request"
			List<String> hints = responseHeader != null ? responseHeader.get(null) : null;
			String hintsString = (hints != null && hints.size() > 0) ? " - " + hints.toString() : "";

			// Shouldn't expose the token in console
			logger.log(Level.WARNING, e.getMessage() + hintsString, e);
			// If we have connectionRetryLimit set to > 0 then retry that many times.
			if (numberOfAttempts <= retryLimit) {
				context.logger.println(String.format(
						"Connection to remote server failed %s, waiting for to retry - %s seconds until next attempt. URL: %s, parameters: %s",
						(responseCode == 0 ? "" : "[" + responseCode + "]"), pollInterval,
						getUrlWithoutParameters(urlString), parmsString));

				// Sleep for 'pollInterval' seconds.
				// Sleep takes miliseconds so need to convert this.pollInterval to milisecopnds
				// (x 1000)
				try {
					// Could do with a better way of sleeping...
					Thread.sleep(pollInterval * 1000);
				} catch (InterruptedException ex) {
					throw ex;
				}

				context.logger.println("Retry attempt #" + numberOfAttempts + " out of " + retryLimit);
				numberOfAttempts++;
				return sendHTTPCall(urlString, requestType, context, postParams, numberOfAttempts, pollInterval,
						retryLimit, overrideAuth, rawRespRef, isCrubmCacheEnabled);

			} else if (numberOfAttempts > retryLimit) {
				// reached the maximum number of retries, time to fail
				throw new ExceedRetryLimitException();
			} else {
				// something failed with the connection and we retried the max amount of
				// times... so throw an exception to mark the build as failed.
				throw e;
			}

		} finally {
			// always make sure we close the connection
			if (conn != null) {
				conn.disconnect();
			}
		}
		return new ConnectionResponse(responseHeader, responseObject, responseCode);
	}

	private static ConnectionResponse tryCall(String urlString, String method, BuildContext context,
			Map<String, Object> params, int pollInterval, int retryLimit, Auth2 overrideAuth, StringBuilder rawRespRef,
			Semaphore lock, boolean isCrubmCacheEnabled) throws IOException, InterruptedException {
		if (lock == null) {
			context.logger.println("calling remote without locking...");
			return sendHTTPCall(urlString, method, context, null, 1, pollInterval, retryLimit, overrideAuth, rawRespRef,
					isCrubmCacheEnabled);
		}
		Boolean isAccquired = null;
		try {
			try {
				isAccquired = lock.tryAcquire(pollInterval, TimeUnit.SECONDS);
				logger.log(Level.FINE, String.format("calling %s in semaphore...", urlString));

				// if we can't lock, just let it go.
			} catch (InterruptedException e) {
				logger.log(Level.WARNING, "fail to accquire lock because of interrupt, skip locking...", e);
				context.logger.println("fail to accquire lock because of interrupt, skip locking...");
			}
			if (isAccquired != null && !isAccquired) {
				logger.warning("fail to accquire lock because of timeout, skip locking...");
				context.logger.println("fail to accquire lock because of timeout, skip locking...");
			}

			ConnectionResponse cr = sendHTTPCall(urlString, method, context, params, 1, pollInterval, retryLimit,
					overrideAuth, rawRespRef, isCrubmCacheEnabled);
			return cr;

		} finally {
			if (isAccquired != null && isAccquired) {
				lock.release();
			}
		}
	}

	public static ConnectionResponse tryPost(String urlString, BuildContext context, Map<String, Object> params,
			int pollInterval, int retryLimit, Auth2 overrideAuth, Semaphore lock, boolean isCrubmCacheEnabled)
			throws IOException, InterruptedException {

		return tryCall(urlString, HTTP_POST, context, params, pollInterval, retryLimit, overrideAuth, null, lock,isCrubmCacheEnabled);
	}

	public static ConnectionResponse tryGet(String urlString, BuildContext context, int pollInterval, int retryLimit,
			Auth2 overrideAuth, Semaphore lock) throws IOException, InterruptedException {
		return tryCall(urlString, HTTP_GET, context, null, pollInterval, retryLimit, overrideAuth, null, lock, false);
	}

	public static String tryGetRawResp(String urlString, BuildContext context, int pollInterval, int retryLimit,
			Auth2 overrideAuth, Semaphore lock) throws IOException, InterruptedException {
		StringBuilder resp = new StringBuilder();
		tryCall(urlString, HTTP_GET, context, null, pollInterval, retryLimit, overrideAuth, resp, lock, false);
		return resp.toString();
	}

	public static ConnectionResponse post(String urlString, BuildContext context, Map<String, Object> params,
			int pollInterval, int retryLimit, Auth2 overrideAuth, boolean isCrubmCacheEnabled) throws IOException, InterruptedException {
		return tryPost(urlString, context, params, pollInterval, retryLimit, overrideAuth, null, isCrubmCacheEnabled);
	}

	public static ConnectionResponse get(String urlString, BuildContext context, int pollInterval, int retryLimit,
			Auth2 overrideAuth) throws IOException, InterruptedException {
		return tryGet(urlString, context, pollInterval, retryLimit, overrideAuth, null);
	}

	public static String getRawResp(String urlString, String requestType, BuildContext context,
			Collection<String> postParams, int numberOfAttempts, int pollInterval, int retryLimit, Auth2 overrideAuth)
			throws IOException, InterruptedException {
		return tryGetRawResp(urlString, context, pollInterval, retryLimit, overrideAuth, null);
	}

}
