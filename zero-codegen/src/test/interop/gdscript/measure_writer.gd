extends SceneTree

# Copy pre/post generated runtimes as baseline.gd and optimized.gd into an isolated project.
const Baseline = preload("res://baseline.gd")
const Optimized = preload("res://optimized.gd")

func _initialize() -> void:
    for round_index in range(4):
        var before = measure(Baseline)
        var after = measure(Optimized)
        assert(before.bytes == after.bytes, "optimized writer changed wire bytes")
        print("round=%d baseline_us=%d optimized_us=%d bytes=%d" % [
            round_index, before.micros, after.micros, after.bytes.size()])
    quit()

func measure(protocol) -> Dictionary:
    var start := Time.get_ticks_usec()
    var writer = protocol.ZeroWriter.new()
    for index in range(1000):
        var marker = writer.begin_object()
        writer.write_int(index)
        writer.write_string("payload")
        writer.end_object(marker)
    assert(writer.is_valid())
    return {"micros": Time.get_ticks_usec() - start, "bytes": writer.to_byte_array()}
