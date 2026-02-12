#!/bin/bash
# if-Gemini Build Script
# ビルドしたJARファイルをbuildsフォルダに蓄積します

cd "$(dirname "$0")"

echo "Building if-Gemini..."
mvn clean package -q

if [ $? -eq 0 ]; then
    # バージョンをpom.xmlから取得
    VERSION=$(grep -m1 "<version>" pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
    JAR_NAME="if-Gemini-${VERSION}.jar"

    # buildsフォルダにコピー
    mkdir -p builds
    cp "target/${JAR_NAME}" "builds/"

    echo ""
    echo "✓ Build successful!"
    echo "  Output: builds/${JAR_NAME}"
    echo ""
    ls -la "builds/"
else
    echo "✗ Build failed!"
    exit 1
fi
