#!/bin/bash
APP_VERSION="0.0.1-SNAPSHOT"
APP_NAME="fueledbychai-switchboard"
DEFAULT_PORT=22222

# Script is in the app root directory
SCRIPT_DIR=$(dirname "$(realpath "$0")")
APP_HOME="$SCRIPT_DIR"

# Configuration
JAR_FILE="$APP_NAME-$APP_VERSION.jar"
JAR_PATH="$APP_HOME/$JAR_FILE"

# Define PID_FILE and LOG_FILE
PID_FILE="$APP_HOME/$APP_NAME.pid"
LOG_FILE="$APP_HOME/logs/$APP_NAME-startup.log"

start() {
    SERVER_PORT="${1:-$DEFAULT_PORT}"

    # Check if JAR file exists
    if [ ! -f "$JAR_PATH" ]; then
        echo "Error: JAR file not found at $JAR_PATH"
        echo "Please ensure the application JAR file is deployed to this location."
        exit 1
    fi

    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p $PID > /dev/null; then
            echo "$APP_NAME is already running (PID: $PID)"
            exit 1
        else
            echo "PID file exists but process is not running. Removing PID file."
            rm -f "$PID_FILE"
        fi
    fi

    # Ensure logs directory exists
    mkdir -p "$APP_HOME/logs"

    echo "Starting $APP_NAME on port $SERVER_PORT..."
    echo "JAR file: $JAR_PATH"
    echo "Log file: $LOG_FILE"
    echo "Using java located at: $(which java)"
    echo "Java version: $(java -version 2>&1 | head -n 1)"

    nohup java -jar "$JAR_PATH" --server.port="$SERVER_PORT" > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    echo "$APP_NAME started (PID: $!)."
    echo "Logs are being written to: $LOG_FILE"
    echo "Application logs are in: $APP_HOME/logs/"
}

stop() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p $PID > /dev/null; then
            echo "Stopping $APP_NAME (PID: $PID)..."
            kill $PID

            # Wait for process to stop gracefully
            sleep 2
            if ps -p $PID > /dev/null; then
                echo "Process didn't stop gracefully, sending SIGKILL..."
                kill -9 $PID
            fi

            rm -f "$PID_FILE"
            echo "$APP_NAME stopped."
        else
            echo "PID file exists but process is not running. Removing PID file."
            rm -f "$PID_FILE"
        fi
    else
        echo "$APP_NAME is not running."
    fi
}

status() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p $PID > /dev/null; then
            echo "$APP_NAME is running (PID: $PID)."

            # Show recent log entries if log file exists
            if [ -f "$LOG_FILE" ]; then
                echo "Recent startup log entries:"
                tail -5 "$LOG_FILE"
            fi
        else
            echo "PID file exists but process is not running."
        fi
    else
        echo "$APP_NAME is not running."
    fi
}

logs() {
    echo "=== Startup Log ==="
    if [ -f "$LOG_FILE" ]; then
        tail -20 "$LOG_FILE"
    else
        echo "Startup log file not found: $LOG_FILE"
    fi

    echo ""
    echo "=== Application Log ==="
    APP_LOG_FILE="$APP_HOME/logs/$APP_NAME.log"
    if [ -f "$APP_LOG_FILE" ]; then
        tail -20 "$APP_LOG_FILE"
    else
        echo "Application log file not found: $APP_LOG_FILE"
    fi
}

case "$1" in
    start)
        start "$2"
        ;;
    stop)
        stop
        ;;
    status)
        status
        ;;
    logs)
        logs
        ;;
    restart)
        stop
        sleep 2
        start "$2"
        ;;
    *)
        echo "Usage: $0 {start|stop|status|logs|restart} [PORT]"
        echo ""
        echo "Commands:"
        echo "  start [PORT]   - Start the application (default port: $DEFAULT_PORT)"
        echo "  stop           - Stop the running application"
        echo "  status         - Show application status"
        echo "  logs           - Show recent application logs"
        echo "  restart [PORT] - Stop and start the application"
        echo ""
        echo "Examples:"
        echo "  $0 start            # Start on default port $DEFAULT_PORT"
        echo "  $0 start 9090       # Start on port 9090"
        echo "  $0 restart           # Restart on default port $DEFAULT_PORT"
        exit 1
        ;;
esac
