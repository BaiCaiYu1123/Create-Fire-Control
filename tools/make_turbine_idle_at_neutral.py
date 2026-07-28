from pathlib import Path


path = (
    Path(__file__).resolve().parents[1]
    / "src/main/java/com/hooya/stabilizedturret/client/GasTurbineSoundManager.java"
)
source = path.read_text(encoding="utf-8")
replacements = {
    """        boolean moving = current > 0.5F || target > 0.5F;

        if (moving && !state.running) {
""": """        if (!state.running) {
""",
    """
        if (state.running && current <= 0.5F && target <= 0.5F) {
            if (state.idle != null) state.idle.halt();
            if (state.run != null) state.run.halt();
            playOneShot(turbine, ModSounds.GAS_TURBINE_STOP.get(), 0.8F);
            state.running = false;
            state.idle = null;
            state.run = null;
        }
""": "\n",
}
for old, new in replacements.items():
    if source.count(old) != 1:
        raise RuntimeError("Expected sound-state block was not found exactly once")
    source = source.replace(old, new)
path.write_text(source, encoding="utf-8")
print(path)
