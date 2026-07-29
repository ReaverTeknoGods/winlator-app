cmake_minimum_required(VERSION 3.22.1)

# NDK r24 predates Android's 16 KB default ELF alignment. Apply the linker
# settings recommended for NDK r27 and older to every native target declared
# by the Winlator subdirectories. Prebuilt libraries under jniLibs are audited
# separately and must be replaced with 16 KB-compatible builds.
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

add_subdirectory(winlator)
add_subdirectory(vortekrenderer)
add_subdirectory(virglrenderer)
add_subdirectory(midihandler)
add_subdirectory(libadrenotools)
add_subdirectory(gladiorenderer)
