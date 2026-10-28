cmake_minimum_required(VERSION 3.10)
project(rtabmap_run CXX)
set(CMAKE_CXX_STANDARD 17)
# RTAB-Map from the ROS 2 Humble apt repo (ros-humble-rtabmap): /opt/ros/humble
find_package(RTABMap REQUIRED PATHS /opt/ros/humble/lib/x86_64-linux-gnu/rtabmap-0.23 /opt/ros/humble/lib/aarch64-linux-gnu/rtabmap-0.23)
add_executable(rtabmap_run rtabmap_run.cpp)
target_link_libraries(rtabmap_run rtabmap::core)
