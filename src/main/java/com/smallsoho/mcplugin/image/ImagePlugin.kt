package com.smallsoho.mcplugin.image

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.internal.api.BaseVariantImpl
import com.smallsoho.mcplugin.image.`interface`.IBigImage
import com.smallsoho.mcplugin.image.utils.*
import com.smallsoho.mcplugin.image.webp.WebpUtils
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future


class ImagePlugin : Plugin<Project> {
    companion object {
        private const val TAG = "ImagePlugin"
        val allImageListReport = HashMap<String, ReportBean>()
    }

    private lateinit var mcImageProject: Project
    private lateinit var mcImageConfig: Config
    private var oldSize: Long = 0
    private var newSize: Long = 0
    val bigImgList = ArrayList<String>()

    var isDebugTask = false
    var isContainAssembleTask = false


    class ReportBean() {
        companion object {
            const val BIG_IN_LIST = "1"
            const val IMAGE_UNKNOWN = ""

            const val STATE_UNKNOWN = ""
            const val STATE_WHITE_LIST = "1"

        }

        var name = ""
        var path = ""
        var isInWhite = STATE_UNKNOWN
        var isBigImage = IMAGE_UNKNOWN //
        var newSize: Long = 0
        var oldSize: Long = 0
        var toWebpState = ""

    }


    override fun apply(project: Project) {

        mcImageProject = project

        //check is library or application
        val hasAppPlugin = project.plugins.hasPlugin("com.android.application")
        val variants = if (hasAppPlugin) {
            (project.property("android") as AppExtension).applicationVariants
        } else {
            (project.property("android") as LibraryExtension).libraryVariants
        }

        //set config
        project.extensions.create("McImageConfig", Config::class.java)
        mcImageConfig = project.property("McImageConfig") as Config

        project.gradle.taskGraph.whenReady {
            it.allTasks.forEach { task ->
                val taskName = task.name
                if (taskName.contains("assemble") || taskName.contains("resguard") || taskName.contains(
                        "bundle"
                    )
                ) {
                    if (taskName.toLowerCase().endsWith("debug") &&
                        taskName.toLowerCase().contains("debug")
                    ) {
                        isDebugTask = true
                    }
                    isContainAssembleTask = true
                    return@forEach
                }
            }
        }

        project.afterEvaluate {
            variants.all { variant ->

                variant as BaseVariantImpl

                checkMcTools(project)

                val mergeResourcesTask = variant.mergeResourcesProvider.get()
                val mcPicTask = project.task("McImage${variant.name.capitalize()}")

                mcPicTask.doLast {

                    //debug enable
                    if (isDebugTask && !mcImageConfig.enableWhenDebug) {
                        LogUtil.log("Debug not run ^_^")
                        return@doLast
                    }

                    //assemble passed
                    if (!isContainAssembleTask) {
                        LogUtil.log("Don't contain assemble task, mcimage passed")
                        return@doLast
                    }

                    LogUtil.log("---- McImage Plugin Start ----")
                    LogUtil.log(mcImageConfig.toString())

                    val dir = variant.allRawAndroidResources.files

                    val cacheList = ArrayList<String>()

                    val imageFileList = ArrayList<File>()

                    for (channelDir: File in dir) {
                        traverseResDir(channelDir, imageFileList, cacheList, object : IBigImage {
                            override fun onBigImage(file: File) {
                                bigImgList.add(file.absolutePath)
                            }
                        })
                    }

                    checkBigImage()

                    val start = System.currentTimeMillis()

                    mtDispatchOptimizeTask(imageFileList)

                    LogUtil.log(
                        TAG,
                        "report all img",
                        "-----ReportBeans-----allImageListReport=${allImageListReport.size} imageFileList=${imageFileList.size}"
                    )

                    val toolsDir = FileUtil.getToolsDir()

                    val csv = "report.csv"
                    val csvFile = File(toolsDir, csv)
                    if (csvFile.exists()) {
                        csvFile.delete()
                    }

                    val fileWriter = FileWriter(csvFile)
                    val out = BufferedWriter(fileWriter)
                    out.write("name,inWhite,toWebpState,isBitImage?,newSize,oldSize,disSize,路径")

                    allImageListReport.keys.forEach {
                        val reportBean = allImageListReport[it]!!
                        out.newLine()
                        val dis = reportBean.newSize - reportBean.oldSize
                        val line =
                            "${reportBean.name},${reportBean.isInWhite},${reportBean.toWebpState},${reportBean.isBigImage},${reportBean.newSize},${reportBean.oldSize},$dis,${reportBean.path}"
                        out.write(line)
                    }
                    out.flush()
                    fileWriter.close()

                    LogUtil.log("save reports to ${csvFile.path}")

                    LogUtil.log(sizeInfo())
                    LogUtil.log("---- McImage Plugin End ----, Total Time(ms) : ${System.currentTimeMillis() - start}")
                }

                //chmod task
                val chmodTaskName = "chmod${variant.name.capitalize()}"
                val chmodTask = project.task(chmodTaskName)
                chmodTask.doLast {
                    //chmod if linux
                    if (Tools.isLinux()) {
                        Tools.chmod()
                    }
                }

                //inject task
                (project.tasks.findByName(chmodTask.name) as Task).dependsOn(
                    mergeResourcesTask.taskDependencies.getDependencies(
                        mergeResourcesTask
                    )
                )
                (project.tasks.findByName(mcPicTask.name) as Task).dependsOn(
                    project.tasks.findByName(
                        chmodTask.name
                    ) as Task
                )
                mergeResourcesTask.dependsOn(project.tasks.findByName(mcPicTask.name))

            }
        }

    }

    private fun traverseResDir(
        file: File,
        imageFileList: ArrayList<File>,
        cacheList: ArrayList<String>,
        iBigImage: IBigImage
    ) {
        if (cacheList.contains(file.absolutePath)) {
            return
        } else {
            cacheList.add(file.absolutePath)
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach {
                if (it.isDirectory) {
                    traverseResDir(it, imageFileList, cacheList, iBigImage)
                } else {
                    filterImage(it, imageFileList, iBigImage)
                }
            }
        } else {
            filterImage(file, imageFileList, iBigImage)
        }
    }

    private fun filterImage(file: File, imageFileList: ArrayList<File>, iBigImage: IBigImage) {
        val inWhiteList = mcImageConfig.whiteList.contains(file.name)
        if (inWhiteList || !ImageUtil.isImage(file)) {
            if (inWhiteList) {
                val reportBean = createReportBean(file)
                reportBean.isInWhite = ReportBean.STATE_WHITE_LIST;
                allImageListReport[reportBean.path] = reportBean // save all img ,add path
            }
            return
        }

        val reportBean = createReportBean(file)
        allImageListReport[reportBean.path] = reportBean // save all img ,add path


        if (((mcImageConfig.isCheckSize && ImageUtil.isBigSizeImage(file, mcImageConfig.maxSize))
                    || (mcImageConfig.isCheckPixels
                    && ImageUtil.isBigPixelImage(
                file,
                mcImageConfig.maxWidth,
                mcImageConfig.maxHeight
            )))
        ) {
            val contains = mcImageConfig.bigImageWhiteList.contains(file.name)


            if (!contains) {
                iBigImage.onBigImage(file)
            } else {
                reportBean.isBigImage = ReportBean.BIG_IN_LIST // add image big
            }

        }
        imageFileList.add(file)
    }

    private fun createReportBean(file: File): ReportBean {
        val reportBean = ReportBean()
        reportBean.path = file.path!!
        reportBean.name = file.name!!
        return reportBean
    }

    private fun mtDispatchOptimizeTask(imageFileList: ArrayList<File>) {
        val notEmpty = bigImgList.isNotEmpty()
        val size = imageFileList.size
        LogUtil.log(
            TAG,
            "mtDispatchOptimizeTask",
            " imageFileList.size=$size bigImgList.notEmpty=$notEmpty"
        )

        if (size == 0 || notEmpty) {
            return
        }
        val coreNum = Runtime.getRuntime().availableProcessors()
        if (imageFileList.size < coreNum || !mcImageConfig.multiThread) {
            for (file in imageFileList) {
                optimizeImage(file)
            }
        } else {
            val results = ArrayList<Future<Unit>>()
            val pool = Executors.newFixedThreadPool(coreNum)
            val part = imageFileList.size / coreNum
            for (i in 0 until coreNum) {
                val from = i * part
                val to = if (i == coreNum - 1) imageFileList.size - 1 else (i + 1) * part - 1
                results.add(pool.submit(Callable<Unit> {
                    for (index in from..to) {
                        optimizeImage(imageFileList[index])
                    }
                }))
            }
            for (f in results) {
                try {
                    f.get()
                } catch (ignore: Exception) {
                    LogUtil.log(ignore)
                }
            }
        }
    }

    private fun optimizeImage(file: File) {
        val path: String = file.path
        if (File(path).exists()) {
            oldSize += File(path).length()
        }
        when (mcImageConfig.optimizeType) {
            Config.OPTIMIZE_WEBP_CONVERT ->
                WebpUtils.securityFormatWebp(file, mcImageConfig, mcImageProject)
            Config.OPTIMIZE_COMPRESS_PICTURE ->
                CompressUtil.compressImg(file)
        }
        countNewSize(path)
    }

    private fun countNewSize(path: String) {
        if (File(path).exists()) {
            newSize += File(path).length()
        } else {
            //转成了webp
            val indexOfDot = path.lastIndexOf(".")
            val webpPath = path.substring(0, indexOfDot) + ".webp"
            if (File(webpPath).exists()) {
                newSize += File(webpPath).length()
            } else {
                LogUtil.log("McImage: optimizeImage have some Exception!!!")
            }
        }
    }

    private fun checkBigImage() {
        if (bigImgList.size != 0) {
            val stringBuffer = StringBuffer(
                "You have big Imgages with big size or large pixels," +
                        "please confirm whether they are necessary or whether they can to be compressed. " +
                        "If so, you can config them into bigImageWhiteList to fix this Exception!!!\n"
            )
            for (i: Int in 0 until bigImgList.size) {
                stringBuffer.append(bigImgList[i])
                stringBuffer.append("\n")
            }
            throw GradleException(stringBuffer.toString())
        }
    }


    private fun checkMcTools(project: Project) {
        if (mcImageConfig.mctoolsDir.isBlank()) {
            FileUtil.setRootDir(project.rootDir.path)
        } else {
            FileUtil.setRootDir(mcImageConfig.mctoolsDir)
        }

        if (!FileUtil.getToolsDir().exists()) {
            throw GradleException("You need put the mctools dir in project root")
        }
    }

    private fun sizeInfo(): String {
        return "->>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n" +
                "before McImage optimize: " + oldSize / 1024 + "KB\n" +
                "after McImage optimize: " + newSize / 1024 + "KB\n" +
                "McImage optimize size: " + (oldSize - newSize) / 1024 + "KB\n" +
                "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<-"


    }
}