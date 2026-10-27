#pragma once

// 电脑上渲染界面用的 Arduino 替身：只提供 main.cpp 用到的那几个
// 函数/对象，实现见 render.cpp（时间是假时钟，由渲染脚本推进）。

#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define LOW 0
#define HIGH 1
#define INPUT 0x01
#define INPUT_PULLUP 0x05

uint32_t millis();
void delay(uint32_t ms);
int digitalRead(uint8_t pin);
void pinMode(uint8_t pin, uint8_t mode);

struct SimSerial {
    void begin(unsigned long) {}
    int printf(const char *fmt, ...) __attribute__((format(printf, 2, 3)));
    void println(const char *s);
    void flush() {}
};
extern SimSerial Serial;

struct SimEsp {
    void restart();
};
extern SimEsp ESP;
