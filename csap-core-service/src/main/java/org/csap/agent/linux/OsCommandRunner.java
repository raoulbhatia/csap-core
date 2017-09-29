package org.csap.agent.linux;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.csap.agent.CSAP;
import org.csap.agent.model.Application;
import org.javasimon.SimonManager;
import org.javasimon.Split;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OsCommandRunner {

	private static final String PERFORMANCE_PREFIX = "java.OsCommandRunner.execute";
	final Logger logger = LoggerFactory.getLogger( OsCommandRunner.class );
	// Token is used for cleaning up script output
	public static final String HEADER_TOKEN = "_P_";

	private int defaultHungProcessIntervalInSeconds = 300;
	private int defaultHungProcessMaxIterations = 6;
	private boolean disableTimeout = false;

	public boolean isDisableTimeout () {
		return disableTimeout;
	}

	public void setDisableTimeout ( boolean disableTimeout ) {
		this.disableTimeout = disableTimeout;
	}

	/**
	 * =========== Ensures OS commands do not run indefinitely
	 */
	private static BasicThreadFactory flushFactory = new BasicThreadFactory.Builder()
		.namingPattern( "CsapOsCommandOutput-%d" )
		.daemon( true )
		.priority( Thread.NORM_PRIORITY )
		.build();
	private static ScheduledExecutorService bufferFlushExecutor = Executors
		.newScheduledThreadPool( 1, flushFactory );

	private static BasicThreadFactory hungFactory = new BasicThreadFactory.Builder()
		.namingPattern( "CsapOsCommandHung-%d" )
		.daemon( true )
		.priority( Thread.NORM_PRIORITY )
		.build();

	private static ScheduledExecutorService hungBashCommandsExecutor = Executors
		.newScheduledThreadPool( 1, hungFactory );

	;

	/**
	 * 
	 * @param hungProcessIntervalInSeconds
	 * @param hungProcessMaxIterations
	 * @param threadName
	 */
	public OsCommandRunner( int hungProcessIntervalInSeconds,
			int hungProcessMaxIterations, String threadName ) {
		this.defaultHungProcessIntervalInSeconds = hungProcessIntervalInSeconds;
		this.defaultHungProcessMaxIterations = hungProcessMaxIterations;
	}

	public boolean isCancel () {
		if ( cancelFile != null && cancelFile.exists() ) {
			logger.warn( "Cancelling job: " + cancelFile.getName() );
			return true;
		}
		return false;
	}

	public final static String OUTPUT_TOKEN = "_CSAP_OUTPUT_"; // scriptRunAsRoot
	public final static String ROOT_USER = "root";
	public final static String ROOT_SCRIPT = "/bin/scriptRunAsRoot.sh";

	public String runUsingDefaultUser ( String name, String[] lines )
			throws IOException {
		File scriptWithPermissions = Application.getStagingFile(
			"/temp/" + name + "-" + System.currentTimeMillis() + ".sh" );

		scriptWithPermissions.getParentFile().mkdirs();

		List<String> script = Arrays.asList( lines );

		Files.write( scriptWithPermissions.toPath(), script, Charset.forName( "UTF-8" ) );

		boolean ableToSet = scriptWithPermissions.setExecutable( true );
		if ( !ableToSet ) {
			logger.warn( "Unable to set execute permisions on : {}", scriptWithPermissions );
		}
		List<String> parmList = new ArrayList<String>();
		parmList.add( "bash" );
		parmList.add( "-c" );
		parmList.add( scriptWithPermissions.getAbsolutePath() );
		String scriptOutput = "\n" + executeString( null, parmList );
		scriptWithPermissions.delete();

		logger.debug( "Script Output; {}", scriptOutput );
		// strip off script info
		int tokenIndex = scriptOutput.indexOf( OUTPUT_TOKEN );
		if ( tokenIndex == -1 ) {
			tokenIndex = 0;
		} else {
			tokenIndex += OUTPUT_TOKEN.length();
		}
		return scriptOutput.substring( tokenIndex );
	}

	public String runUsingRootUser ( String name, String[] lines )
			throws IOException {

		File scriptWithPermissions = Application.getStagingFile(
			"/temp/" + name + "-" + System.currentTimeMillis() + ".sh" );

		scriptWithPermissions.setExecutable( true );
		scriptWithPermissions.getParentFile().mkdirs();

		List<String> script = Arrays.asList( lines );

		Files.write( scriptWithPermissions.toPath(), script, Charset.forName( "UTF-8" ) );

		boolean ableToSet = scriptWithPermissions.setExecutable( true );
		if ( !ableToSet ) {
			logger.warn( "Unable to set execute permisions on : {}", scriptWithPermissions );
		}

		String scriptOutput = runUsingRootUser( scriptWithPermissions, null );
		scriptWithPermissions.delete();

		logger.debug( "Script Output; {}", scriptOutput );
		// strip off script info
		int tokenIndex = scriptOutput.indexOf( OUTPUT_TOKEN );
		if ( tokenIndex == -1 ) {
			tokenIndex = 0;
		} else {
			tokenIndex += OUTPUT_TOKEN.length();
		}
		return scriptOutput.substring( tokenIndex );
	}

	/**
	 * 
	 * Fall back to using run user if root is not available
	 * 
	 * @param targetFile
	 * @param outputFm
	 * @return
	 */
	public String runUsingRootUser ( File targetFile, OutputFileMgr outputFm ) {

		File scriptPath = Application.getStagingFile( ROOT_SCRIPT );
		List<String> parmList = new ArrayList<String>();
		if ( Application.isRunningAsRoot() ) {
			parmList.add( "/usr/bin/sudo" );
			parmList.add( scriptPath.getAbsolutePath() );
			parmList.add( targetFile.getAbsolutePath() );
			parmList.add( ROOT_USER );
		} else {
			parmList.add( "bash" );
			parmList.add( "-c" );
			parmList.add( targetFile.getAbsolutePath() );
			// parmList.add( scriptPath.getAbsolutePath()
			// + " " + targetFile.getAbsolutePath()
			// + " " + "ROOT_NOT_AVAILABLE" );
		}

		// some commands we skip creating output files.
		BufferedWriter bw = null;
		if ( outputFm != null ) {
			bw = outputFm.getBufferedWriter();
		}

		String output = "\n" + executeString( bw, parmList );
		return output;
	}

	public final static String CANCEL_SUFFIX = ".cancel";

	public static String runCancellable ( int timeOutSeconds, String chownUserid, File targetFile, OutputFileMgr outputFm ) {

		File scriptPath = Application.getStagingFile( "/bin/scriptRunAsRoot.sh" );
		List<String> parmList = new ArrayList<String>();
		if ( Application.isRunningAsRoot() ) {
			parmList.add( "/usr/bin/sudo" );
			parmList.add( scriptPath.getAbsolutePath() );
			parmList.add( targetFile.getAbsolutePath() );
			parmList.add( chownUserid );
		} else {
			parmList.add( "bash" );
			parmList.add( "-c" );
			// parmList.add( targetFile.getAbsolutePath() );
			parmList.add( scriptPath.getAbsolutePath()
					+ " " + targetFile.getAbsolutePath()
					+ " " + chownUserid );
		}

		// some commands we skip creating output files.
		BufferedWriter bw = null;
		if ( outputFm != null ) {
			bw = outputFm.getBufferedWriter();
		}

		// We check to see if process was cancelled every 5 seconds.
		int hungCheckInterval = 5;
		int numIterations = (timeOutSeconds / hungCheckInterval) + 1;
		OsCommandRunner localRunner = new OsCommandRunner( hungCheckInterval, numIterations, "OsScript" );
		File cancelFile = new File( targetFile.getAbsolutePath() + CANCEL_SUFFIX );
		localRunner.setCancelFile( cancelFile );
		String output = "\n" + localRunner.executeString( bw, parmList );
		return output;
	}

	private File cancelFile = null;

	public void setCancelFile ( File cancelFile ) {
		this.cancelFile = cancelFile;
	}

	private class FlushOutputRunnable implements Runnable {

		BufferedWriter outputWriter;

		public FlushOutputRunnable( BufferedWriter outputWriter ) {
			this.outputWriter = outputWriter;
		}

		public void run () {
			if ( outputWriter != null ) {
				try {
					logger.debug( "Flushing output to disk" );
					outputWriter.flush();
				} catch (IOException e) {
					logger.error( "Failed to flush", e );
				}
			}
		}

	}

	private class CheckForHungProcess implements Runnable {

		public Process process;

		private int maxNumberOfIterationsLookingForOutput = 10;
		private int intervalDuration = 10;

		public CheckForHungProcess( int intervalCount, int intervalDuration ) {
			this.intervalDuration = intervalDuration;
			this.maxNumberOfIterationsLookingForOutput = intervalCount;
		}

		// public boolean foundOutput = true;
		public int count = 0;

		public boolean wasTerminated = false;

		public boolean isWasTerminated () {
			return wasTerminated;
		}

		public void run () {

			// && foundOutput - some processes will not output anything in
			// interval
			if ( !isCancel() && count < maxNumberOfIterationsLookingForOutput ) {
				count++;
				logger.debug( "Resetting timeout" );
				// foundOutput = false;
			} else {
				// foundOutput = false;
				if ( count < maxNumberOfIterationsLookingForOutput ) {
					logger.warn( "Exceeded interval limit of " + intervalDuration
							+ " seconds" );
				} else {
					logger.warn( "Exceeded Max  max total seconds:"
							+ maxNumberOfIterationsLookingForOutput * maxNumberOfIterationsLookingForOutput );
				}

				if ( isDisableTimeout() ) {
					logger.warn( "Timeouts are currently disabled. Verify your process is not hung by looking for output in logs." );
					return;
				}

				wasTerminated = true;
				process.destroy();

				try {
					Thread.sleep( 1000 );
				} catch (Exception e) {
				}
				// process.exitValue() ; // will throw exception if it is still
				// open
				List<String> parmList = new ArrayList<String>();
				File scriptPath = Application.getStagingFile( "/bin/killStaging.sh" );
				File workingDir = scriptPath.getParentFile().getParentFile();
				
				parmList.add( "bash" );
				// parmList.add("-c") ;
				parmList.add( scriptPath.getAbsolutePath() );

				String results = executeString( parmList, workingDir, null,
					null, null );

				logger.warn( "Results from {}: \n{}", scriptPath.getAbsolutePath(), results );
				// throw new RuntimeException(
				// "Did not receive output - process could be hung, aborting");
			}
		}
	}

	// use current working dir for file
	public String executeString ( BufferedWriter outputWriter, List<String> params ) {

		return executeString( params, Application.getProcessingTempFolder(), null, null,
			this.defaultHungProcessIntervalInSeconds,
			this.defaultHungProcessMaxIterations, outputWriter );

	}

	public String executeString ( List<String> params, File workingDir ) {

		return executeString( params, workingDir, null, null,
			this.defaultHungProcessIntervalInSeconds,
			this.defaultHungProcessMaxIterations, null );

	}

	/**
	 * Uses defaults for timeouts
	 *
	 * @param params
	 * @param workingDir
	 * @param response
	 * @param session
	 * @return
	 */
	public String executeString (	List<String> params, File workingDir,
									HttpServletResponse response, HttpSession session, BufferedWriter outputWriter ) {

		return executeString( params, workingDir, response, session,
			this.defaultHungProcessIntervalInSeconds,
			this.defaultHungProcessMaxIterations, outputWriter );

	}

	public String executeString (	List<String> params, File workingDir,
									HttpServletResponse response, HttpSession session,
									int hungProcessIntervalInSeconds, int hungProcessMaxIterations,
									BufferedWriter outputWriter ) {

		return executeString( params, null, workingDir, response, session,
			this.defaultHungProcessIntervalInSeconds,
			this.defaultHungProcessMaxIterations, outputWriter );
	}

	private Process lastProcessHandle = null;

	public void shutDown () {

		if ( lastProcessHandle == null ) {
			return;
		}
		try {
			lastProcessHandle.destroy();
			logger.info( "Process Handle Destroyed" );
		} catch (Exception e) {
			logger.error( "Failed shutting down", e );
		}
	}

	public String executeString (	List<String> params, Map<String, String> envVarsMap, File workingDir,
									HttpServletResponse response, HttpSession session,
									int hungProcessIntervalInSeconds, int hungProcessMaxIterations,
									BufferedWriter outputWriter ) {

		String summary = params.get( 0 );
		String commandName = params.get( 0 );
		if ( params.size() >= 2 && params.get( 1 ).equals( "-c" ) ) {
			summary = params.toString();

			commandName = params.get( 2 );
			if ( commandName.indexOf( " " ) != -1 ) {
				commandName = commandName.substring( 0, params.get( 2 ).indexOf( " " ) );
				if ( commandName.contains( "scriptRunAsRoot" ) && params.size() >= 3 ) {
					commandName = "root." + params.get( 2 );
				}
			}
		} else if ( params.size() >= 2 ) {
			summary = params.get( 1 );
			commandName = params.get( 1 );
			if ( commandName.contains( "scriptRunAsRoot" ) && params.size() >= 3 ) {
				commandName = "root." + params.get( 2 );
			}
		}

		if ( commandName.contains( "_script.sh_" ) ) {
			commandName = "userScript";
		}
		if ( commandName.contains( "/" ) || commandName.contains( "\\" ) ) {
			commandName = (new File( commandName )).getName();
		}
		Split osCommandTimer = null;
		String timerName = PERFORMANCE_PREFIX + "." + commandName;

		Split allCommandsTimer = null;
		if ( commandName.contains( "jobRunner" ) ) {
			timerName = "csap.service.jobs";
		} else {
			// track jobRunner separately
			allCommandsTimer = SimonManager.getStopwatch( PERFORMANCE_PREFIX ).start();
		}

		int temporySuffix = timerName.indexOf( "-" );

		if ( temporySuffix > 0 ) {
			timerName = timerName.substring( 0, temporySuffix );
		}

		try {
			osCommandTimer = SimonManager.getStopwatch( timerName ).start();
		} catch (Exception e) {
			logger.warn( "Invalid name: " + commandName, e );
		}

		// logger.info("isAppend:" + isAppend + " outputFile:" + outputFile +
		// " params:" + params) ;
		logger.debug( "Invoking: {}", params );

		ProcessBuilder processBuilder = new ProcessBuilder( params );
		Map<String, String> processBuilderEnvVars = processBuilder.environment();

		if ( envVarsMap == null ) {
			processBuilderEnvVars.put( "noCsapVars", "true" );
		} else {
			processBuilderEnvVars.putAll( envVarsMap );
		}

		processBuilder.redirectErrorStream( true );
		processBuilder.directory( workingDir );

		Process processHandle = null;
		String s = null;
		BufferedReader stdOutAndErrReader = null; // stdout and error combined
		InputStreamReader isReader = null;

		if ( Application.isRunningOnDesktop() ) {

			logger.debug( "Running in test mode, skipping bash command: {}", params.toString() );

			String desktopOutput = "Desktop Stubbed out: \n\n\t Parameters: " + params.toString();
			if ( envVarsMap != null ) {
				desktopOutput += "\n\n\t Environment variables: " + envVarsMap.toString();
			}

			try {
				String debugFilter = "rebuild";
				// debugFilter = "scriptRunAsRoot";
				// // hook for testing start sync on windows
				if ( outputWriter != null && params.get( 1 ).contains( debugFilter ) ) {
					Thread.sleep( 5000 );
					for ( int i = 0; i < 5; i++ ) {
						Thread.sleep( 2000 );

						outputline( outputWriter, "OsCommandRunniner Hook for windows: Sleeping 2s: "
								+ i + "\n" );
						outputWriter.flush();

					}
					return desktopOutput;
				} else {
					// Sleep can cause slowness on desktop due to inconsistent
					// handling comment out for quicker dev.
					Thread.sleep( 500 ); // 500 will provide that most local
					// testing will show some delay
				}

				outputline( outputWriter, desktopOutput );
			} catch (Exception e) {
				if ( e instanceof InterruptedException ) {
					logger.warn( "Got an InterruptedException, OK if JVM is being shutdown" );
				} else {
					logger.error( "Failed processing", e );
				}

			}
			return desktopOutput;
		}

		// Note that __Result is used for string filtering in JSPs. Do NOT
		// change.
		StringBuffer results = new StringBuffer( "Executing OS command on host "
				+ Application.getHOST_NAME() + ":" + summary + HEADER_TOKEN
				+ System.getProperty( "line.separator" ) + System.getProperty( "line.separator" ) );

		CheckForHungProcess checkForHungProcess = new CheckForHungProcess(
			hungProcessMaxIterations, hungProcessIntervalInSeconds );

		ScheduledFuture<?> hungProcessCheckerHandle = hungBashCommandsExecutor
			.scheduleWithFixedDelay( checkForHungProcess,
				hungProcessIntervalInSeconds, hungProcessIntervalInSeconds,
				TimeUnit.SECONDS );

		// We need to periodically flush output so logs are updated. UI tails
		// for progress updates
		ScheduledFuture<?> bufferFlushWorker = bufferFlushExecutor
			.scheduleWithFixedDelay( new FlushOutputRunnable( outputWriter ),
				5, 5,
				TimeUnit.SECONDS );

		try {

			// very important to pick up paths from parent!!
			logger.debug( "spawning: {} in working Dir: {}", params.toString(), workingDir.getAbsolutePath() );

			outputline( outputWriter, results.toString() );

			processHandle = processBuilder.start();
			lastProcessHandle = processHandle;
			isReader = new InputStreamReader( processHandle.getInputStream() );
			stdOutAndErrReader = new BufferedReader( isReader );

			int lineNum = 0;

			checkForHungProcess.process = processHandle; // tiny race condition
			// but 10s
			// shold be plenty

			while ((s = stdOutAndErrReader.readLine()) != null) {
				// logger.debug("Line: " + s);

				outputline( outputWriter, (lineNum++) + "  :  " + s );
				// checkForHungProcess.foundOutput = true; // reset counter
				results.append( s );
				results.append( System.getProperty( "line.separator" ) );

				// hook so that restarts do no kill them selves
				if ( s.indexOf( "XXXYYYZZZ_AdminController" ) != -1 ) {
					logger.info(
						"Found terminate flag in script output: {}. Issueing processHandle.destroy() to kill script if it has not already exited",
						"XXXYYYZZZ_AdminController" );
					processHandle.destroy();
					break;
				}

			}
			logger.debug( "Done: {}", params.get( 0 ) );
		} catch (IOException e) {
			logger.error( "Process Killed: workingDir: {}, parameters: {}, \n {}",
				workingDir.getAbsolutePath(), params, CSAP.getCsapFilteredStackTrace( e ) );
			logger.debug( "Full exception", e );
		} finally {

			lastProcessHandle = null;
			hungProcessCheckerHandle.cancel( true );
			bufferFlushWorker.cancel( true );

			if ( checkForHungProcess.isWasTerminated() ) {
				results.append( CSAP.CONFIG_PARSE_ERROR
						+ " == Aborted processing due to either inactivity or max processing time limit exceeded\n"
						+ " You could try again assuming lengthy build was due to staging jars from maven." );
			}
			if ( isReader != null ) {
				try {
					logger.debug( "Results: {}", results );
					if ( response != null ) {
						response.getWriter().println( results );
					}
					isReader.close();
				} catch (IOException e) {
					logger.error( "failed closing reader", e );
				}
			}
			if ( processHandle != null ) {
				try {
					// hung thread monitor will interrupt if needed
					int exit = processHandle.waitFor();
					// logger.info("Exit Code: " + exit);

					logger.debug( "Exit Code: " + exit );
					processHandle.getInputStream().close();
					processHandle.getOutputStream().close();
					processHandle.getErrorStream().close();
				} catch (Exception e) {
					logger.info( "OS process cleanup: workingDir: {}, parameters: {}, \n {}",
						workingDir.getAbsolutePath(), params,
						CSAP.getCsapFilteredStackTrace( e ) );
				}
				processHandle.destroy();
			}

			if ( stdOutAndErrReader != null ) {
				try {
					stdOutAndErrReader.close();
				} catch (IOException e) {
					logger.error( "failed closing reader", e );
				}
			}
		}

		if ( allCommandsTimer != null ) {
			allCommandsTimer.stop();
		}

		if ( osCommandTimer != null ) {
			osCommandTimer.stop();
		}
		return results.toString();

	}

	private void outputline ( BufferedWriter outputWriter, String s )
			throws IOException {

		if ( outputWriter != null ) {
			outputWriter.write( s + "\n" );
		}
		return;
	}

}
