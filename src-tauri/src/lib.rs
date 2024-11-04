use std::error::Error;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::path::PathBuf;
use tauri::App;
use tauri::Manager;
use tauri::Emitter;
use tauri::Listener;
use tauri::path::BaseDirectory;
use tauri::webview::WebviewWindow;
use tokio::io::{AsyncReadExt};
use tokio::fs;
use serde::{Serialize, Deserialize};

#[derive(Serialize, Deserialize, Debug)]
struct Action {
    cmd: String,
    args: Vec<String>,
}

#[derive(Clone, Serialize, Deserialize, Debug)]
struct RenderPayload {
    render_string: String,
    string_hash: String,
    is_first_render: bool,
}

#[tauri::command]
async fn post_render(
    handle: tauri::AppHandle,
    window: tauri::Window, 
    width: i32,
    height: i32,
    hash: String
) {
    let finalWidth = width + 40;
    let finalHeight = height + 40;
    let size = tauri::PhysicalSize { width: finalWidth, height: finalHeight };
    window.set_size(size);
    window.show();
    let mut size_file = handle.path().resolve("ztr", BaseDirectory::Data).unwrap();
    size_file.push(hash);
    let contents = serde_json::to_string(&size).unwrap();
    fs::write(size_file, contents.as_bytes()).await;
}

#[tauri::command]
fn on_resize(window: tauri::Window) {
    window.center();
}

#[tauri::command]
fn show_window(window: tauri::Window) {
    window.show();
}

pub fn hash_string(s: &String) -> String {
    let mut hasher = DefaultHasher::new();
    s.hash(&mut hasher);
    serde_json::to_string(&hasher.finish()).unwrap()
}

pub fn setup<R: tauri::Runtime>(raw_app: &mut App<R>) -> Result<(), Box<dyn Error>> {
    let app = raw_app.handle().clone();
    tauri::async_runtime::spawn(async move {
        let data_dir = app.path().resolve("ztr", BaseDirectory::Data).unwrap();
        fs::create_dir_all(data_dir.clone()).await;
        let mut window_option: Option<WebviewWindow<R>> = None;
        let mut stdin_done = false;
        let mut is_first_render = true;
        let (tx, rx) = tokio::sync::oneshot::channel::<()>();
        let mut config = app
            .config()
            .app
            .windows
            .get(0)
            .unwrap()
            .clone();
        /*
        let mut size_file = data_dir.clone();
        size_file.push(string_hash.clone());
        match tokio::fs::read_to_string(size_file).await {
            Ok(contents) => {
                match serde_json::from_str::<tauri::PhysicalSize<f64>>(&contents) {
                    Ok(size) => {
                        config.width = size.width;
                        config.height = size.height;
                    },
                    _ => {},
                }
            },
            _ => {},
        };
        */
        app.once("ready", |_| {
            tx.send(());
        });
        let window = tauri::WebviewWindowBuilder::from_config(
            &app, &config
        ).unwrap().build().unwrap();
        window_option = Some(window);
        let _ = rx.await;
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
                    let render_string = &action.args[0];
                    let string_hash = hash_string(render_string);
                    let render_payload = RenderPayload {
                        render_string: render_string.clone(),
                        string_hash,
                        is_first_render,
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
        .invoke_handler(tauri::generate_handler![post_render, on_resize, show_window])
        .setup(setup)
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
