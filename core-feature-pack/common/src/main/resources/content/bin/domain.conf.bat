rem ### -*- batch file -*- ######################################################
rem #                                                                          ##
rem #  JBoss Bootstrap Script Configuration                                    ##
rem #                                                                          ##
rem #############################################################################

rem # $Id: run.conf.bat 88820 2009-05-13 15:25:44Z dimitris@jboss.org $

rem #
rem # This batch file is executed by run.bat to initialize the environment
rem # variables that run.bat uses. It is recommended to use this file to
rem # configure these variables, rather than modifying run.bat itself.
rem #

rem #
rem # Specify the JBoss Profiler configuration file to load.
rem #
rem # Default is to not load a JBoss Profiler configuration file.
rem #
rem set "PROFILER=%JBOSS_HOME%\bin\jboss-profiler.properties"

rem #
rem # Specify the location of the Java home directory (it is recommended that
rem # this always be set). If set, then "%JAVA_HOME%\bin\java" will be used as
rem # the Java VM executable; otherwise, "%JAVA%" will be used (see below).
rem #
rem set "JAVA_HOME=C:\opt\jdk1.6.0_23"

rem #
rem # Specify the exact Java VM executable to use - only used if JAVA_HOME is
rem # not set. Default is "java".
rem #
rem set "JAVA=C:\opt\jdk1.6.0_23\bin\java"

rem #
rem # Specify options to pass to the Java VM. Note, there are some additional
rem # options that are always passed by domain.bat.
rem #

if not "x%JAVA_OPTS%" == "x" (
  echo "JAVA_OPTS already set in environment; overriding default settings with values: %JAVA_OPTS%"
  goto JAVA_OPTS_SET
)

if "x%JBOSS_JAVA_SIZING%" == "x" (
    rem # JVM memory allocation pool parameters - modify as appropriate.
    set "JBOSS_JAVA_SIZING=-Xms64M -Xmx512M"
)
set "JAVA_OPTS=%JBOSS_JAVA_SIZING%"

rem # Prefer IPv4
set "JAVA_OPTS=%JAVA_OPTS% -Djava.net.preferIPv4Stack=true"

rem # Make Byteman classes visible in all module loaders
rem # This is necessary to inject Byteman rules into AS7 deployments
set "JAVA_OPTS=%JAVA_OPTS% -Djboss.modules.system.pkgs=org.jboss.byteman"

set "JAVA_OPTS=%JAVA_OPTS% -Djava.awt.headless=true"

rem # Use JBoss Modules lockless mode
rem set "JAVA_OPTS=%JAVA_OPTS% -Djboss.modules.lockless=true"

rem # Default JDK_SERIAL_FILTER settings
if "x%JDK_SERIAL_FILTER%" == "x" (
  set "JDK_SERIAL_FILTER=maxbytes=10485760;maxdepth=128;maxarray=100000;maxrefs=300000"
)

rem # Uncomment the following line to disable jdk.serialFilter settings
rem set "DISABLE_JDK_SERIAL_FILTER=true"

:JAVA_OPTS_SET

rem The ProcessController process uses its own set of java options
if "x%PROCESS_CONTROLLER_JAVA_OPTS%" == "x" (
  set "PROCESS_CONTROLLER_JAVA_OPTS=%JAVA_OPTS%"
)

rem The HostController process uses its own set of java options
if "x%HOST_CONTROLLER_JAVA_OPTS%" == "x" (
  set "HOST_CONTROLLER_JAVA_OPTS=%JAVA_OPTS%"
)

rem # Uncomment this to run with a security manager enabled
rem set "SECMGR=true"

rem # Sample JPDA settings for remote socket debugging
rem set "PROCESS_CONTROLLER_JAVA_OPTS=%PROCESS_CONTROLLER_JAVA_OPTS% -agentlib:jdwp=transport=dt_socket,address=8788,server=y,suspend=n"
rem set "HOST_CONTROLLER_JAVA_OPTS=%HOST_CONTROLLER_JAVA_OPTS% -agentlib:jdwp=transport=dt_socket,address=8787,server=y,suspend=n"

rem # Sample JPDA settings for shared memory debugging
rem set "PROCESS_CONTROLLER_JAVA_OPTS=%PROCESS_CONTROLLER_JAVA_OPTS% -agentlib:jdwp=transport=dt_shmem,address=jboss,server=y,suspend=n"
rem set "HOST_CONTROLLER_JAVA_OPTS=%HOST_CONTROLLER_JAVA_OPTS% -agentlib:jdwp=transport=dt_shmem,address=jboss,server=y,suspend=n"

rem # Uncomment the following line to enable debug traces for the Management CLI script file
rem set "INST_MGR_SCRIPT_DEBUG=true"

rem # Uncomment the following line to configure the grace period (in seconds) before applying a candidate server
rem set "INST_MGR_SCRIPT_WINDOWS_COUNTDOWN=10"
