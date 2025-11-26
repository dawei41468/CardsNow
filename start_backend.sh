#!/bin/bash

# Script to build and start the CardsNow backend server

echo "Building backend..."
cd cardsnow-backend
./gradlew shadowJar

if [ $? -eq 0 ]; then
    echo "Build successful. Starting server..."
    # Check if port 8080 is in use and kill the process
    PORT=8080
    PID=$(lsof -t -i :$PORT)
    if [ ! -z "$PID" ]; then
        echo "Port $PORT is already in use by process $PID. Killing it..."
        kill -9 $PID
        sleep 1 # Give a moment for the port to be released
    fi

    java -jar build/libs/cardsnow-backend-all.jar
else
    echo "Build failed. Please check the errors above."
fi