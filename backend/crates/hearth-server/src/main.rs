use std::net::SocketAddr;
use std::path::PathBuf;

use clap::Parser;
use hearth_server::{app, system_clock, AppState, Db};
use tracing_subscriber::EnvFilter;

/// Hearth home hub: LAN-only family sync + reports API.
#[derive(Parser, Debug)]
#[command(version, about)]
struct Args {
    /// SQLite database file.
    #[arg(long, default_value = "hearth.db", env = "HEARTH_DB")]
    db: PathBuf,
    /// Address to listen on (LAN interface).
    #[arg(long, default_value = "0.0.0.0:8787", env = "HEARTH_BIND")]
    bind: SocketAddr,
    /// Name advertised over mDNS.
    #[arg(long, default_value = "Hearth Hub")]
    name: String,
    /// Do not advertise over mDNS.
    #[arg(long)]
    no_mdns: bool,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();
    let args = Args::parse();
    let db = Db::open(&args.db)?;
    let listener = tokio::net::TcpListener::bind(args.bind).await?;
    let port = listener.local_addr()?.port();

    #[cfg(feature = "mdns")]
    let _mdns = if args.no_mdns {
        None
    } else {
        match hearth_server::mdns::advertise(&args.name, port) {
            Ok(d) => Some(d),
            Err(e) => {
                tracing::warn!(error = %e, "mDNS advertisement failed; phones need the hub address manually");
                None
            }
        }
    };

    tracing::info!(db = %args.db.display(), bind = %args.bind, port, "Hearth hub listening");
    axum::serve(listener, app(AppState::new(db, system_clock())))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;
    Ok(())
}
