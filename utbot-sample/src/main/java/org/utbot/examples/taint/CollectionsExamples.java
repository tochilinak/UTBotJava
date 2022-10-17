package org.utbot.examples.taint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.utbot.examples.taint.BadSink.writeIntoBd;

public class CollectionsExamples {
    public void sinkWithList(int i, boolean shouldBeSanitized) {
        String param = BadSource.getEnvironment("data");

        List<String> list = new ArrayList<>();

        list.add("safe");
        list.add(param);
        list.add("safe");

        if (i > 0) {
            list.remove(1);
        }

        String value = list.get(1);

        if (shouldBeSanitized) {
            value = TaintCleaner.removeTaintMark(value);
        }

        writeIntoBd(value);
    }
}
