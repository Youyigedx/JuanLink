package com.juanlink.composeui.draw

import com.juanlink.core.draw.WireElement
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.ElementDto
import io.ak1.drawbox.domain.model.toDto
import io.ak1.drawbox.domain.model.toElement

/**
 * DrawBox Element ↔ core WireElement 双向映射。
 *
 * 复用 DrawBox 现成的 `toDto()` / `ElementDto.toElement()` 转换（见
 * Serialization.kt），core 保持零 DrawBox 依赖。WireElement 字段与
 * SerializableElement/ElementDto 逐字段一致，转换是直通赋值。
 */
fun Element.toWire(includeImageData: Boolean = true): WireElement =
    toDto().toWire(includeImageData = includeImageData)

fun ElementDto.toWire(includeImageData: Boolean = true): WireElement = WireElement(
    id = id,
    type = type,
    zIndex = zIndex,
    points = points,
    strokeColor = strokeColor,
    strokeWidth = strokeWidth,
    alpha = alpha,
    shapeType = shapeType,
    fillColor = fillColor,
    rotation = rotation,
    cornerRadius = cornerRadius,
    strokeStyle = strokeStyle,
    bend = bend,
    startBinding = startBinding,
    endBinding = endBinding,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    samples = samples,
    strokeEnabled = strokeEnabled,
    // 图片元素在内容变更时先发「占位 upsert」（imageData 置空），字节经分块单独传输
    imageData = if (includeImageData) imageData else null,
    intrinsicWidth = intrinsicWidth,
    intrinsicHeight = intrinsicHeight,
    opacity = opacity,
    text = text,
    fontFamilyKey = fontFamilyKey,
    fontSize = fontSize,
    alignment = alignment,
    textTopLeft = textTopLeft,
    wrapWidth = wrapWidth,
)

fun WireElement.toElement(): Element = ElementDto(
    id = id,
    type = type,
    zIndex = zIndex,
    points = points,
    strokeColor = strokeColor,
    strokeWidth = strokeWidth,
    alpha = alpha,
    shapeType = shapeType,
    fillColor = fillColor,
    rotation = rotation,
    cornerRadius = cornerRadius,
    strokeStyle = strokeStyle,
    bend = bend,
    startBinding = startBinding,
    endBinding = endBinding,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    samples = samples,
    strokeEnabled = strokeEnabled,
    imageData = imageData,
    intrinsicWidth = intrinsicWidth,
    intrinsicHeight = intrinsicHeight,
    opacity = opacity,
    text = text,
    fontFamilyKey = fontFamilyKey,
    fontSize = fontSize,
    alignment = alignment,
    textTopLeft = textTopLeft,
    wrapWidth = wrapWidth,
).toElement()
