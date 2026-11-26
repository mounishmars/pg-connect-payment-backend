#!/bin/bash
# PG Connect Payment Service - Startup Script
# Usage: ./start-pgconnect.sh [cashfree-client-id] [cashfree-client-secret] [cashfree-webhook-secret]

set -e

echo "🚀 Starting PG Connect Payment Service..."
echo ""

# Configure paths
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
export PATH="/opt/homebrew/opt/postgresql@15/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

# Database configuration
export DB_URL='jdbc:postgresql://localhost:5432/pgconnect'
export DB_USERNAME='mkuma153'
export DB_PASSWORD=''

# Cashfree configuration (from command line arguments or use sandbox)
export CASHFREE_BASE_URL='https://sandbox.cashfree.com/pg'
export CASHFREE_CLIENT_ID="${1:-your_sandbox_app_id}"
export CASHFREE_CLIENT_SECRET="${2:-your_sandbox_secret}"
export CASHFREE_WEBHOOK_SECRET="${3:-your_webhook_secret}"

# Check prerequisites
echo "✓ Checking prerequisites..."
java -version 2>&1 | head -1
mvn -v 2>&1 | head -1
psql --version

echo ""
echo "✓ Database configuration:"
echo "  URL: $DB_URL"
echo "  User: $DB_USERNAME"

# Check if PostgreSQL is running
echo ""
echo "✓ Checking PostgreSQL status..."
if ! psql -U "$DB_USERNAME" -d pgconnect -c "SELECT 1" > /dev/null 2>&1; then
  echo "  PostgreSQL not responding. Starting service..."
  brew services start postgresql@15
  sleep 2
fi

echo "  Connected to pgconnect database"

# Check database schema
echo ""
echo "✓ Checking database schema..."
TABLE_COUNT=$(psql -U "$DB_USERNAME" -d pgconnect -t -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public';" 2>/dev/null || echo "0")
echo "  Found $TABLE_COUNT tables"

if [ "$TABLE_COUNT" -eq 0 ]; then
  echo "  (Tables will be created by Flyway on first run)"
fi

echo ""
echo "🌐 Cashfree Configuration:"
echo "  Base URL: $CASHFREE_BASE_URL"
if [ "$CASHFREE_CLIENT_ID" = "your_sandbox_app_id" ]; then
  echo "  ⚠️  WARNING: Using placeholder credentials!"
  echo "  Please provide your Cashfree sandbox credentials:"
  echo "    ./start-pgconnect.sh <client-id> <client-secret> <webhook-secret>"
else
  echo "  Client ID: $(echo $CASHFREE_CLIENT_ID | cut -c1-8)..."
fi

echo ""
echo "📂 Project directory: $(pwd)"
echo ""
echo "Starting Spring Boot application on http://localhost:8080"
echo "Press Ctrl+C to stop"
echo ""
echo "========================================================================================"
echo ""

# Run the application
mvn spring-boot:run
