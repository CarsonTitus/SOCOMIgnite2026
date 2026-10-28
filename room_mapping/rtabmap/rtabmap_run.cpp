// rtabmap_run: runs RTAB-Map (visual odometry + graph SLAM with loop closure) on one of our recording folders
// (color/*.jpg aligned to depth, depth/*.png 16-bit mm) and saves its database. Export the model with
// rtabmap-export. Built and called by rtabmap/run.sh.
//
//   rtabmap_run <recording> <out.db> <fx> <fy> <cx> <cy> <width> <height> [--Param/Name value ...]
//
// When visual odometry loses track it resets and RTAB-Map starts a new map; loop closure detection then
// relocalises the new map against the earlier ones (by appearance) and merges them, which our pipeline can't do.
#include <rtabmap/core/CameraModel.h>
#include <rtabmap/core/Odometry.h>
#include <rtabmap/core/OdometryInfo.h>
#include <rtabmap/core/Parameters.h>
#include <rtabmap/core/Rtabmap.h>
#include <rtabmap/core/camera/CameraRGBDImages.h>
#include <rtabmap/utilite/UDirectory.h>
#include <rtabmap/utilite/UFile.h>
#include <rtabmap/utilite/ULogger.h>

#include <cstdio>
#include <memory>
#include <string>

using namespace rtabmap;

int main(int argc, char *argv[])
{
	if (argc < 9)
	{
		std::printf("Usage: rtabmap_run <recording> <out.db> <fx> <fy> <cx> <cy> <width> <height> [--Param value ...]\n");
		return 1;
	}
	ULogger::setType(ULogger::kTypeConsole);
	ULogger::setLevel(ULogger::kWarning);

	const std::string recording = argv[1], db = argv[2];
	const CameraModel model("d455", std::stod(argv[3]), std::stod(argv[4]), std::stod(argv[5]), std::stod(argv[6]),
	                        CameraModel::opticalRotation(), 0.0, cv::Size(std::stoi(argv[7]), std::stoi(argv[8])));
	const std::string calibDir = UDirectory::getDir(db);
	model.save(calibDir);

	ParametersMap params = Parameters::parseArguments(argc, argv);
	// depth PNGs are already in mm, which is RTAB-Map's unit for 16-bit depth: scale factor 1
	CameraRGBDImages camera(recording + "/color", recording + "/depth", 1.0f, 0.0f, CameraModel::opticalRotation());
	if (!camera.init(calibDir, "d455"))
	{
		std::printf("Could not open %s\n", recording.c_str());
		return 1;
	}

	if (UFile::exists(db))
		UFile::erase(db);
	std::unique_ptr<Odometry> odom(Odometry::create(params));
	Rtabmap rtabmap;
	rtabmap.init(params, db);

	int frames = 0, lost = 0, maps = 1;
	bool wasLost = false;
	SensorData data = camera.takeData();
	while (data.isValid())
	{
		data.setStamp(frames / 30.0);  // frame number / 30: exported poses (rtabmap-export --poses_camera) map back to frames
		OdometryInfo info;
		Transform pose = odom->process(data, &info);
		if (pose.isNull())
		{
			++lost;
			wasLost = true;
		}
		else
		{
			if (wasLost)  // odometry reset itself (Odom/ResetCountdown): continue in a new map
			{
				rtabmap.triggerNewMap();
				++maps;
				wasLost = false;
			}
			rtabmap.process(data, pose, info.reg.covariance);
		}
		if (++frames % 100 == 0)
			std::printf("  frame %5d  odometry lost %d  maps %d  nodes in memory %d\n", frames, lost, maps,
			            (int)rtabmap.getWMSize() + (int)rtabmap.getSTMSize());
		data = camera.takeData();
	}
	std::printf("%d frames, odometry lost on %d, %d map(s) started; saving %s\n", frames, lost, maps, db.c_str());
	rtabmap.close(true);
	return 0;
}
