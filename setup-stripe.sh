#!/bin/bash
# Stripe Setup Quick Start
# This script helps you set up Stripe for the PG Connect Payment Starter

set -e

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║           Stripe Payment Gateway Setup Guide                  ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

echo "📋 STEP 1: Create Stripe Account (if you haven't already)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. Go to: https://dashboard.stripe.com/register"
echo "2. Sign up with your email"
echo "3. Verify your email address"
echo ""
echo "Press Enter when you've created your Stripe account..."
read

echo ""
echo "🔑 STEP 2: Get API Keys"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. Log in to Stripe Dashboard: https://dashboard.stripe.com"
echo "2. Click 'Developers' (top right corner)"
echo "3. Click 'API keys'"
echo "4. You'll see two keys under 'Standard keys':"
echo "   - Publishable key (starts with pk_test_)"
echo "   - Secret key (starts with sk_test_)"
echo ""
echo "5. Copy your SECRET KEY (the one starting with sk_test_)"
echo ""
echo "Enter your STRIPE_API_KEY (Secret key):"
read -s STRIPE_API_KEY
echo ""

echo ""
echo "🔗 STEP 3: Get Webhook Secret"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. Still in Developers > API keys"
echo "2. Scroll down to 'Webhooks' section"
echo "3. Click 'Add an endpoint' or 'Create endpoint'"
echo "4. Enter endpoint URL: http://localhost:8080/api/v1/webhooks/stripe"
echo "5. Select events:"
echo "   - charge.succeeded"
echo "   - charge.failed"
echo "   - payment_intent.succeeded"
echo "   - payment_intent.payment_failed"
echo "6. Click 'Create endpoint'"
echo "7. Click 'Reveal' to show the WEBHOOK SIGNING SECRET (starts with whsec_)"
echo ""
echo "Enter your STRIPE_WEBHOOK_SECRET:"
read -s STRIPE_WEBHOOK_SECRET
echo ""

echo ""
echo "✅ CONFIGURATION SUMMARY"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "API Key: ${STRIPE_API_KEY:0:20}..."
echo "Webhook Secret: ${STRIPE_WEBHOOK_SECRET:0:20}..."
echo ""

echo "🔐 Saving to ~/.zshrc_pgconnect_stripe..."
cat > ~/.zshrc_pgconnect_stripe << EOF
# Java 21 configuration
export PATH="/opt/homebrew/opt/openjdk@21/bin:\$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

# PostgreSQL 15 configuration
export PATH="/opt/homebrew/opt/postgresql@15/bin:\$PATH"

# Database configuration
export DB_URL='jdbc:postgresql://localhost:5432/pgconnect'
export DB_USERNAME='mkuma153'
export DB_PASSWORD=''

# Stripe configuration
export STRIPE_API_KEY='$STRIPE_API_KEY'
export STRIPE_WEBHOOK_SECRET='$STRIPE_WEBHOOK_SECRET'

echo "✓ Stripe environment configured"
echo "  Stripe API Key: ${STRIPE_API_KEY:0:20}..."
echo "  DB URL: \$DB_URL"
echo ""
EOF

echo "✓ Configuration saved to ~/.zshrc_pgconnect_stripe"
echo ""

echo "🚀 NEXT STEPS"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "1. Navigate to your project:"
echo "   cd ~/Documents/pg/pg-connect-payment-starter"
echo ""
echo "2. Load Stripe environment:"
echo "   source ~/.zshrc_pgconnect_stripe"
echo ""
echo "3. Install dependencies:"
echo "   mvn clean install"
echo ""
echo "4. Run the application:"
echo "   mvn spring-boot:run"
echo ""
echo "5. The app will start on: http://localhost:8080"
echo ""

echo "🧪 TEST WITH STRIPE TEST CARDS"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Successful Payment:"
echo "  Card: 4242 4242 4242 4242"
echo "  Expiry: 12/25 (or any future date)"
echo "  CVC: 123"
echo "  ZIP: 12345"
echo ""
echo "Failed Payment:"
echo "  Card: 4000 0000 0000 0002"
echo "  Expiry: 12/25"
echo "  CVC: 123"
echo ""

echo "📚 For detailed setup, see: STRIPE_MIGRATION_GUIDE.md"
echo ""
echo "✨ Setup complete! Ready to test with Stripe."
echo ""
