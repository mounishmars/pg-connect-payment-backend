# PG Connect Payment Starter - Setup Guide

## ✅ Prerequisites Installed

Your system is now configured with all required prerequisites:

- **Java 21.0.13.1** (OpenJDK via Homebrew)
- **Maven 3.10.2**
- **PostgreSQL 15.20** (running)
- **Database**: `pgconnect` created and ready

## 🚀 Quick Start

### 1. Set Up Environment

Add this to your shell configuration or run before starting the application:

```bash
# Java 21 configuration
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

# PostgreSQL 15 configuration
export PATH="/opt/homebrew/opt/postgresql@15/bin:$PATH"

# Database configuration
export DB_URL='jdbc:postgresql://localhost:5432/pgconnect'
export DB_USERNAME='mkuma153'
export DB_PASSWORD=''
```

**Shortcut**: Use the prepared configuration:
```bash
source ~/.zshrc_pgconnect_full
```

### 2. Add Cashfree Credentials

Edit `~/.zshrc_pgconnect_full` and add your sandbox credentials:

```bash
export CASHFREE_BASE_URL='https://sandbox.cashfree.com/pg'
export CASHFREE_CLIENT_ID='your_sandbox_app_id'
export CASHFREE_CLIENT_SECRET='your_sandbox_secret'
export CASHFREE_WEBHOOK_SECRET='your_webhook_secret'
```

### 3. Run the Application

```bash
source ~/.zshrc_pgconnect_full
cd /Users/mkuma153/Downloads/pg-connect-payment-starter
mvn spring-boot:run
```

The application will:
1. Start Spring Boot on `http://localhost:8080`
2. Automatically run Flyway migrations (`V1__payment_foundation.sql`)
3. Create required tables: `users`, `owner_profiles`, `invoices`, `payment_transactions`, `payment_splits`

## 📋 Verify Setup

```bash
# Check Java
java -version

# Check Maven
mvn -version

# Check PostgreSQL
psql -U mkuma153 -d pgconnect -c "SELECT version();"

# List tables (after running the app)
psql -U mkuma153 -d pgconnect -c "\dt"
```

## 🔧 Useful Commands

### Start PostgreSQL (if not already running)
```bash
brew services start postgresql@15
```

### Stop PostgreSQL
```bash
brew services stop postgresql@15
```

### Access PostgreSQL CLI
```bash
psql -U mkuma153 -d pgconnect
```

### Reset Database (dangerous! drops all data)
```bash
dropdb -U mkuma153 pgconnect
/opt/homebrew/opt/postgresql@15/bin/createdb pgconnect
```

### View Application Logs
Application logs will appear in the terminal when running `mvn spring-boot:run`

### Run Tests
```bash
source ~/.zshrc_pgconnect_full
mvn test
```

## 🌐 API Endpoints

Once running, the application provides:

**Create Payment**:
```bash
curl -X POST http://localhost:8080/api/v1/customer/rent/payments \
  -H "Content-Type: application/json" \
  -H "X-Authenticated-User: <tenant-uuid>" \
  -H "Idempotency-Key: <unique-key>" \
  -d '{
    "invoiceId": "<invoice-uuid>",
    "amount": 2500.00,
    "customerPhone": "9876543210"
  }'
```

**Webhook** (Cashfree POST):
```
POST http://localhost:8080/api/v1/webhooks/cashfree
```

## 📚 Project Structure

```
src/
├── main/
│   ├── java/com/pgconnect/   # Spring Boot application code
│   └── resources/
│       ├── application.yml     # Spring configuration
│       └── db/migration/
│           └── V1__payment_foundation.sql  # Database schema
└── test/
    └── java/                   # Unit and integration tests
```

## 🔐 Important Security Notes

⚠️ **This is a starter scaffold, NOT production-ready**. Before deployment:

1. Replace hardcoded demo headers (`X-Authenticated-User`) with Spring Security/JWT
2. Implement durable async webhook worker with Cashfree state verification
3. Add invoice allocation for partial payments
4. Implement Easy Split settlement (minimum 2-minute wait)
5. Add double-entry ledger and settlement reconciliation
6. Enable HTTPS and secure credential storage

See README.md for the full list of deliberately unimplemented features.

## 🐛 Troubleshooting

### "createdb: command not found"
```bash
export PATH="/opt/homebrew/opt/postgresql@15/bin:$PATH"
```

### PostgreSQL connection refused
```bash
# Check if PostgreSQL is running
brew services list | grep postgresql

# Start if not running
brew services start postgresql@15
```

### Java version mismatch
```bash
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
```

### Maven build fails
```bash
# Clean cache and rebuild
mvn clean install
```

## 📝 Database Schema

The initial migration creates:

- **users** - User identity (demo table, replace with real identity store)
- **owner_profiles** - Owner/merchant profiles (demo table, replace with real owner store)
- **invoices** - Rent payment invoices with balance tracking
- **payment_transactions** - Cashfree payment records with status
- **payment_splits** - Easy Split vendor allocations

For details, see `src/main/resources/db/migration/V1__payment_foundation.sql`

## 🔗 Resources

- [Spring Boot Documentation](https://spring.io/projects/spring-boot)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [Cashfree PG API](https://www.cashfree.com/docs/api-reference/payments/latest)
- [Flyway Documentation](https://flywaydb.org/documentation/)
- [Maven Documentation](https://maven.apache.org/guides/)
