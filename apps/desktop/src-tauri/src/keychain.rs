use anyhow::{Context, Result};

const SERVICE: &str = "com.dailyplan.desktop";
const ACCOUNT: &str = "api-token";

fn entry() -> Result<keyring::Entry> {
    keyring::Entry::new(SERVICE, ACCOUNT).context("open system credential store")
}

pub fn token() -> Result<Option<String>> {
    match entry()?.get_password() {
        Ok(token) => {
            let token = token.trim().to_string();
            if token.is_empty() {
                Ok(None)
            } else {
                Ok(Some(token))
            }
        }
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => Err(error).context("read access token"),
    }
}

pub fn set_token(token: &str) -> Result<()> {
    entry()?
        .set_password(token.trim())
        .context("save access token")
}

pub fn delete_token() -> Result<()> {
    match entry()?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(error) => Err(error).context("delete access token"),
    }
}
