//! Advertise the hub on the home network so phones can find it without configuration.

use mdns_sd::{ServiceDaemon, ServiceInfo};

pub const SERVICE_TYPE: &str = "_hearth._tcp.local.";

/// Registers `_hearth._tcp.local.` on all interfaces. Keep the daemon alive for as long as the
/// hub runs; dropping it withdraws the advertisement.
pub fn advertise(instance: &str, port: u16) -> Result<ServiceDaemon, mdns_sd::Error> {
    let daemon = ServiceDaemon::new()?;
    let host = format!("{}.local.", instance.replace(' ', "-").to_lowercase());
    let info = ServiceInfo::new(SERVICE_TYPE, instance, &host, "", port, &[("v", "1")][..])?
        .enable_addr_auto();
    daemon.register(info)?;
    Ok(daemon)
}
