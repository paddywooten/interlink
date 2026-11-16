//! `interlink-web`: InterLink in a browser, on any OS.
//!
//! Runs the laptop side of the phone link (the same backend as the desktop app)
//! and serves the UI over HTTP. Run it, open the printed link, pair the phone.

use interlink_lib::web::{self, WebConfig};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

const HELP: &str = "\
InterLink web — the InterLink laptop app, in your browser.

USAGE:
    interlink-web [OPTIONS]

OPTIONS:
    --port <N>          Web port (default 27350; the next free one is used if busy)
    --listen <ADDR>     Address to listen on (default 127.0.0.1 = this computer only).
                        0.0.0.0 lets other devices on the network open the page over
                        plain HTTP — only do that on a network you trust.
    --no-open           Don't open a browser; just print the link
    --config-dir <DIR>  Where pairing and settings live (default: shared with the
                        InterLink desktop app, so one pairing serves both)
    -h, --help          Show this help

The phone still connects to this computer on TCP/UDP 27341 and UDP 27342, exactly
as it does for the desktop app — only one of the two can run at a time.
";

fn main() {
    let mut port = web::DEFAULT_PORT;
    let mut ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
    let mut open_browser = true;
    let mut config_dir = web::default_config_dir();

    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        let mut value = |name: &str| -> String {
            args.next().unwrap_or_else(|| {
                eprintln!("{name} needs a value\n\n{HELP}");
                std::process::exit(2)
            })
        };
        match a.as_str() {
            "-h" | "--help" => {
                print!("{HELP}");
                return;
            }
            "--no-open" => open_browser = false,
            "--port" => {
                port = value("--port").parse().unwrap_or_else(|_| {
                    eprintln!("--port must be a number between 1 and 65535");
                    std::process::exit(2)
                })
            }
            "--listen" => {
                ip = value("--listen").parse().unwrap_or_else(|_| {
                    eprintln!("--listen must be an IP address, e.g. 127.0.0.1 or 0.0.0.0");
                    std::process::exit(2)
                })
            }
            "--config-dir" => config_dir = value("--config-dir").into(),
            other => {
                eprintln!("unknown option {other}\n\n{HELP}");
                std::process::exit(2);
            }
        }
    }

    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let rt = tokio::runtime::Runtime::new().expect("cannot start the async runtime");
    let cfg = WebConfig { listen: SocketAddr::new(ip, port), open_browser, config_dir };
    if let Err(e) = rt.block_on(web::run(cfg)) {
        eprintln!("\ninterlink-web: {e}");
        std::process::exit(1);
    }
}
