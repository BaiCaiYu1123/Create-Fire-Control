from pathlib import Path


path = (
    Path(__file__).resolve().parents[1]
    / "src/main/java/com/hooya/stabilizedturret/content/turbine/GasTurbineBlock.java"
)
source = path.read_text(encoding="utf-8")
old = """        if (!level.isClientSide
                && level.getBlockEntity(pos) instanceof GasTurbineBlockEntity turbine) {
            turbine.toggleOutput(hit.getDirection());
        }
"""
if source.count(old) != 1:
    raise RuntimeError("Legacy turbine wrench toggle block was not found exactly once")
source = source.replace(
    old,
    """        // Wrench clicks are consumed by GasTurbineBindingHandler and are
        // reserved for connecting the advanced wireless signal processor.
"""
)
path.write_text(source, encoding="utf-8")
print(path)
