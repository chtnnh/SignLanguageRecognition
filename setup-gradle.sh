#!/bin/bash

# Gradle wrapper script
# This ensures the project uses the correct Gradle version

cd "$(dirname "$0")"

# Download gradle wrapper if it doesn't exist
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "Downloading Gradle Wrapper..."
    mkdir -p gradle/wrapper
    curl -L -o gradle/wrapper/gradle-wrapper.jar https://github.com/gradle/gradle/raw/v8.0.0/gradle/wrapper/gradle-wrapper.jar
fi

# Make gradlew executable
chmod +x gradlew

echo "Gradle setup complete. You can now run:"
echo "./gradlew build"
echo "or open the project in Android Studio"
