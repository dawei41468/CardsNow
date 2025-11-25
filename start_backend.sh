#!/bin/bash

# Script to build and start the CardsNow backend server

echo "Building backend..."
cd cardsnow-backend
./gradlew shadowJar

if [ $? -eq 0 ]; then
    echo "Build successful. Starting server..."
    java -jar build/libs/cardsnow-backend-all.jar
else
    echo "Build failed. Please check the errors above."
fi