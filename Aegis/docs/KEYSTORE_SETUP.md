# Aegis — Production RSA-4096 Keystore Setup Guide

This guide describes how to generate a production RSA-4096 Android release signing keystore, base64-encode it, and configure the necessary secrets in GitHub Actions repository settings.

---

## 1. Keystore Generation (RSA-4096)

Run `keytool` (included with OpenJDK) on a secure workstation:

```bash
keytool -genkeypair -v \
  -keystore aegis-release.jks \
  -alias aegis-release-key \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storetype JKS \
  -dname "CN=Aegis Mobile Hub, OU=Aegis, O=Aegis Organization, L=La Paz, ST=LP, C=BO"
```

You will be prompted for:
1. `keystore password` (and confirmation)
2. `key password` (can be the same as keystore password)

> **Important**: Store this `.jks` file and passwords securely in a password manager or offline vault. Once deployed to users, losing the private key prevents releasing APK updates.

---

## 2. Base64 Encoding the Keystore

Convert the binary keystore file into a single base64 string:

```bash
base64 -w 0 aegis-release.jks > aegis-release.jks.b64
```

Copy the entire content of `aegis-release.jks.b64`.

---

## 3. Configuring GitHub Actions Secrets

Navigate to your GitHub repository:
**Settings → Secrets and variables → Actions → New repository secret**

Add the following 4 secrets:

| Secret Name | Description | Example Value |
|---|---|---|
| `KEYSTORE_BASE64` | Complete base64 string of `aegis-release.jks` | `MIIKqwIBAzCCCm8GCSqGSIb3DQE...` |
| `KEY_ALIAS` | Key alias specified during generation | `aegis-release-key` |
| `KEY_PASSWORD` | Password protecting the private key | *Your chosen key password* |
| `STORE_PASSWORD` / `KEYSTORE_PASSWORD` | Password protecting the keystore file | *Your chosen keystore password* |

---

## 4. Verification in GitHub Actions

The workflow `.github/workflows/build-apk.yml` detects `KEYSTORE_BASE64` in the `build-release` job:
- If present, it decodes `KEYSTORE_BASE64` to `app/app/companion-release.keystore`, configures the credentials, runs `gradle assembleRelease`, and publishes `aegis-release` APK artifact.
- If omitted, the release step safely skips with a notice, leaving debug artifacts intact.
