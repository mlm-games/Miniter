use miniter_domain::Project;
use miniter_media_native::export::export_project;
use std::path::Path;

fn main() {
    let mut args = std::env::args().skip(1);
    let Some(project_path) = args.next() else {
        eprintln!("usage: export_from_json <project.mntr> <output.mp4>");
        std::process::exit(2);
    };
    let Some(output_path) = args.next() else {
        eprintln!("usage: export_from_json <project.mntr> <output.mp4>");
        std::process::exit(2);
    };

    let json = std::fs::read_to_string(&project_path).expect("failed to read project file");
    let mut project = Project::from_json(&json).expect("failed to parse project json");
    project.export_profile.output_path = output_path.clone();

    export_project(
        &project,
        Path::new(&output_path),
        || false,
        |pct| {
            if pct % 10_000 == 0 || pct == 1 || pct == 100_000 {
                println!("progress={pct}");
            }
        },
    )
    .expect("export failed");

    println!("ok: {output_path}");
}
