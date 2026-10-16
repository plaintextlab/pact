// Embeds the app icon into pact.exe on Windows.
fn main() {
    println!("cargo:rerun-if-changed=assets/icon.ico");
    #[cfg(windows)]
    {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("assets/icon.ico");
        res.set("ProductName", "Pact");
        res.set("FileDescription", "Pact");
        res.compile().expect("embedding the Windows icon");
    }
}
