plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Dependency graphs for true-sim: producers resolved by key, nodes culled and ordered by their accesses."

coverage {
    minimum = 1.0
}
