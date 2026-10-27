# 在电脑上用真正的固件界面代码（src/main.cpp）渲染各状态截图，不用烧板子。
# 原理见 render.cpp 开头的注释。需要 CMake、gcc/g++ 和 zlib 开发头文件，别的都不用。
#
#   cmake -B build                           # 第一次先配置（之后改了代码直接 build 就行）
#   cmake --build build -j                   # 编译并渲染 17 张静态图到 out/（2 倍放大）
#   cmake --build build -j --target anim     # 动态效果，输出动画 PNG 到 out/anim/
#   cmake -B build -DSCALE=1                 # 原始 240×240
#   cmake -B build -DLVGL_DIR=<路径>          # lib/lvgl 子模块没初始化时，指向别处的同版本 LVGL
#
# LVGL 第一次编译要一会儿（几百个 .c），之后只重编改过的文件。

cmake_minimum_required(VERSION 3.20)
project(knob_simulate C CXX)

set(FW_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(LVGL_DIR ${FW_DIR}/lib/lvgl CACHE PATH "LVGL 源码目录（跟固件同版本）")
set(OUT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/out CACHE PATH "截图输出目录")
set(SCALE 2 CACHE STRING "截图放大倍数")

file(GLOB_RECURSE LVGL_SRCS CONFIGURE_DEPENDS ${LVGL_DIR}/src/*.c)
if(NOT LVGL_SRCS)
    message(FATAL_ERROR "${LVGL_DIR} 下没有 LVGL 源码：先 git submodule update --init firmware/lib/lvgl，或者 cmake -B build -DLVGL_DIR=<路径>")
endif()
file(GLOB FONT_SRCS CONFIGURE_DEPENDS ${FW_DIR}/src/fonts/*.c)

# LVGL 和字体都用固件那份 lv_conf.h（lib/），LV_CONF_INCLUDE_SIMPLE 让 LVGL 按 -I 找它
add_library(lvgl STATIC ${LVGL_SRCS})
target_compile_definitions(lvgl PUBLIC LV_CONF_INCLUDE_SIMPLE)
target_include_directories(lvgl PUBLIC ${FW_DIR}/lib ${LVGL_DIR})
target_compile_options(lvgl PRIVATE -O2 -w)

# render.cpp 直接 #include 了 ../src/main.cpp，头文件依赖由编译器的 depfile 跟踪，
# 改了 main.cpp / config.h / lv_conf.h 都会自动重编
find_package(ZLIB REQUIRED)
add_executable(render render.cpp ${FONT_SRCS})
set_target_properties(render PROPERTIES CXX_STANDARD 17 CXX_EXTENSIONS ON)
target_compile_definitions(render PRIVATE BOARD_PCB)
target_include_directories(render PRIVATE stub ${FW_DIR}/include ${FW_DIR}/src)
target_compile_options(render PRIVATE -O2 -w)
target_link_libraries(render PRIVATE lvgl ZLIB::ZLIB m)

# 默认构建就渲染静态图：先清空 out/ 再出图，不留上一次的旧图
add_custom_target(screenshots ALL
    COMMAND ${CMAKE_COMMAND} -E rm -rf ${OUT_DIR}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${OUT_DIR}
    COMMAND render ${OUT_DIR} ${SCALE}
    DEPENDS render
    USES_TERMINAL)

# 动画：转圈、长按提交、固件升级、BOOT 清配对 → out/anim/*.png（动画 PNG，render 直接写出）
add_custom_target(anim
    COMMAND ${CMAKE_COMMAND} -E rm -rf ${OUT_DIR}/anim
    COMMAND ${CMAKE_COMMAND} -E make_directory ${OUT_DIR}/anim
    COMMAND render ${OUT_DIR}/anim ${SCALE} anim
    DEPENDS render
    USES_TERMINAL)
