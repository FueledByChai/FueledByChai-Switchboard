#!/bin/bash
set -e

if [ $# -lt 1 ]; then
    echo "Usage: $0 <VERSION>"
    echo "Example: $0 0.0.1"
    exit 1
fi

VERSION=$1

APP_NAME="fueledbychai-switchboard"
DESTINATION_DIR="/home/bitnami/apps/$APP_NAME"
KEY_PATH="/Users/RobTerpilowski/.ssh/Singapore-8.pem"
PROJECT_PATH="/Users/RobTerpilowski/Code/JavaProjects/FueledByChai-Switchboard"
JAR_PATH="$PROJECT_PATH/target/$APP_NAME-$VERSION-SNAPSHOT.jar"
LAUNCH_SCRIPT="$PROJECT_PATH/scripts/mosaic-arb-generic.sh"
SERVER=singapore8.fueledbychaitrading.com
REMOTE_PATH="$DESTINATION_DIR/$VERSION"

# Validate prerequisites
echo "Validating deployment prerequisites..."

if [ ! -f "$KEY_PATH" ]; then
    echo "Error: SSH key file not found: $KEY_PATH"
    exit 1
fi

if [ ! -f "$LAUNCH_SCRIPT" ]; then
    echo "Error: Launch script not found: $LAUNCH_SCRIPT"
    exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
    echo "Error: JAR file not found: $JAR_PATH"
    echo "Please build the project first with: mvn clean package -DskipTests"
    exit 1
fi

echo "Prerequisites validated successfully!"
echo ""

echo "Deploying $APP_NAME v$VERSION to $SERVER..."

# Create remote directory
echo "Ensuring remote directory exists: $REMOTE_PATH"
OUTPUT=$(ssh -i "$KEY_PATH" bitnami@$SERVER "mkdir -p $REMOTE_PATH && ls -ld $REMOTE_PATH" 2>&1)
if [ $? -ne 0 ]; then
    echo "Failed to create or verify remote directory: $REMOTE_PATH"
    echo "$OUTPUT"
    exit 1
fi
echo "$OUTPUT"

echo "Copying files to $REMOTE_PATH/"

# Copy JAR file
scp -i "$KEY_PATH" "$JAR_PATH" "bitnami@$SERVER:$REMOTE_PATH/$APP_NAME-$VERSION-SNAPSHOT.jar"

# Copy launch script
scp -i "$KEY_PATH" "$LAUNCH_SCRIPT" "bitnami@$SERVER:$REMOTE_PATH/switchboard.sh"

# Make the script executable
ssh -i "$KEY_PATH" bitnami@$SERVER "chmod +x $REMOTE_PATH/switchboard.sh"

echo ""
echo "Deployment completed successfully!"
echo ""
echo "Deployment Summary:"
echo "- Server: $SERVER"
echo "- Version: $VERSION"
echo "- Remote path: $REMOTE_PATH"
echo ""
echo "To manage the application:"
echo "1. SSH to server: ssh -i $KEY_PATH bitnami@$SERVER"
echo "2. Navigate to app directory: cd $REMOTE_PATH"
echo "3. Use management commands:"
echo "   ./switchboard.sh start         # Start on default port 22222"
echo "   ./switchboard.sh start 9090    # Start on custom port"
echo "   ./switchboard.sh stop          # Stop the application"
echo "   ./switchboard.sh status        # Check application status"
echo "   ./switchboard.sh logs          # View logs"
echo "   ./switchboard.sh restart       # Restart the application"
