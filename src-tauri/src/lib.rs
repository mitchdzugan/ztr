use std::error::Error;
use std::hash::{DefaultHasher, Hash, Hasher};
use tauri::App;
use tauri::Manager;
use tauri::Emitter;
use tauri::Listener;
use tauri::webview::WebviewWindow;
use tokio::io::{AsyncReadExt};
// use tokio::fs;
use serde::{Serialize, Deserialize};

#[derive(Serialize, Deserialize, Debug)]
struct Action {
    cmd: String,
    args: Vec<String>,
}

#[derive(Clone, Serialize, Deserialize, Debug)]
struct RenderPayload {
    render_string: String,
    hash_string: String,
    is_first_render: bool,
}

#[tauri::command]
fn post_render(width: i32, height: i32, hash: String) {
    println!("width: {}, height: {}, hash: {}", width, height, hash);
}

pub fn hash_string(s: &String) -> String {
    let mut hasher = DefaultHasher::new();
    s.hash(&mut hasher);
    serde_json::to_string(&hasher.finish()).unwrap()
}

pub fn setup<R: tauri::Runtime>(raw_app: &mut App<R>) -> Result<(), Box<dyn Error>> {
    let app = raw_app.handle().clone();
    tauri::async_runtime::spawn(async move {
        let mut window_option: Option<WebviewWindow<R>> = None;
        let mut stdin_done = false;
        let mut is_first_render = true;
        loop {
            let mut buf: Vec<u8> = Vec::new();
            loop {
                match tokio::io::stdin().read_u8().await {
                    Err(_) => { stdin_done = true; break },
                    Ok(u8) => {
                        if u8 == 0  { stdin_done = true; break; } // EOF
                        if u8 == 10 { break; } // newline
                        buf.push(u8);
                    },
                }
            }
            if stdin_done { break; }
            let line = String::from_utf8(buf).unwrap();
            let action: Action = match serde_json::from_str(&line) {
                Err(_) => {
                    eprintln!("Invalid input line: {}", line);
                    Action { cmd: String::from("noop"), args: vec![] }
                },
                Ok(action) => action
            };
            match action.cmd.as_str() {
                "noop" => {},
                "done" => { break; },
                "render" => {
                    match window_option {
                        Some(_) => {},
                        None => {
                            let (tx, rx) = tokio::sync::oneshot::channel::<()>();
                            let window = tauri::WebviewWindowBuilder::from_config(
                                &app, 
                                &app.config().app.windows.get(0).unwrap().clone()
                            ).unwrap().build().unwrap();
                            window_option = Some(window);
                            app.once("ready", |_| {
                                tx.send(());
                            });
                            let _ = rx.await;
                        }
                    }
                    let render_string = &action.args[0];
                    let render_payload = RenderPayload {
                        render_string: render_string.clone(),
                        hash_string: hash_string(render_string),
                        is_first_render: is_first_render,
                    };
                    app.emit("render", render_payload);
                    is_first_render = false;
                },
                _ => {
                    eprintln!("Invalid cmd: {}\n{:?}", action.cmd, action);
                }
            }

        }
        app.exit(0);
    });
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .invoke_handler(tauri::generate_handler![post_render])
        .setup(setup)
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
