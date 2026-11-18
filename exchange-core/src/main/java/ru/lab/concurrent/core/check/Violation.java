package ru.lab.concurrent.core.check;

import java.util.List;

/** Нарушение системного инварианта: код + сколько раз + человеческое объяснение. */
public record Violation(String code, int count, String humanReadable) {

    @Override
    public String toString() {
        return code + " x" + count + " — " + humanReadable;
    }
}
