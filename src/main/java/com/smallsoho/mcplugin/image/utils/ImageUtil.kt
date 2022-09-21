package com.smallsoho.mcplugin.image.utils

import com.smallsoho.mcplugin.image.Const
import java.awt.Dimension
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.FileImageInputStream

import javax.imageio.stream.ImageInputStream


class ImageUtil {

    companion object {
        private const val SIZE_TAG = "SizeCheck"

        fun isImage(file: File): Boolean {
            return (file.name.endsWith(Const.JPG) ||
                    file.name.endsWith(Const.PNG) ||
                    file.name.endsWith(Const.JPEG)
                    ) && !file.name.endsWith(Const.DOT_9PNG)
        }

        fun isJPG(file: File): Boolean {
            return file.name.endsWith(Const.JPG) || file.name.endsWith(Const.JPEG)
        }

        fun isAlphaPNG(filePath: File): Boolean {
            return if (filePath.exists()) {
                try {
                    val img = ImageIO.read(filePath)
                    img.colorModel.hasAlpha()
                } catch (e: Exception) {
                    LogUtil.log(e.message!!)
                    false
                }
            } else {
                false
            }
        }

        fun isBigSizeImage(imgFile: File, maxSize: Float): Boolean {
            if (isImage(imgFile)) {
                if (imgFile.length() >= maxSize) {
                    LogUtil.log(SIZE_TAG, imgFile.path, true.toString())
                    return true
                }
            }
            return false
        }

        fun isBigPixelImage(imgFile: File, maxWidth: Int, maxHeight: Int): Boolean {
            if (isImage(imgFile)) {
                try {

                    val sourceImg = getImageDim(imgFile.path)
                    if (sourceImg!!.height > maxHeight || sourceImg.width > maxWidth) {
                        return true
                    }
                } catch (e: Exception) {
                    LogUtil.log("isBigPixelImage() called with: imgFile = $imgFile, maxWidth = $maxWidth, maxHeight = $maxHeight")
                    LogUtil.log(e)
                    throw e
                }
            }
            return false
        }

        /**
         * 获取图片的分辨率
         *
         * @param path
         * @return
         */
        fun getImageDim(path: String?): Dimension? {
            var result: Dimension? = null
            val suffix = getFileSuffix(path)
            //解码具有给定后缀的文件
            val iter: Iterator<ImageReader> = ImageIO.getImageReadersBySuffix(suffix)
            println(path)
            if (iter.hasNext()) {
                val reader: ImageReader = iter.next()
                try {
                    val stream: ImageInputStream = FileImageInputStream(
                        File(
                            path
                        )
                    )
                    reader.setInput(stream)
                    val width: Int = reader.getWidth(reader.getMinIndex())
                    val height: Int = reader.getHeight(reader.getMinIndex())
                    result = Dimension(width, height)
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    reader.dispose()
                }
            }
            return result
        }

        /**
         * 获得图片的后缀名
         * @param path
         * @return
         */
        private fun getFileSuffix(path: String?): String? {
            var result: String? = null
            if (path != null) {
                result = ""
                if (path.lastIndexOf('.') != -1) {
                    result = path.substring(path.lastIndexOf('.'))
                    if (result.startsWith(".")) {
                        result = result.substring(1)
                    }
                }
            }
            return result
        }

    }
}