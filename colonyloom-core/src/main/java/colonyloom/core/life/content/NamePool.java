package colonyloom.core.life.content;

import colonyloom.core.life.json.Obj;
import java.util.ArrayList;
import java.util.List;

public record NamePool(String culture, List<String> male, List<String> female, List<Family> families) {
    public record Family(String male, String female) {
        public Family { check(male); check(female); }
        public String form(Sex sex) { return sex == Sex.MALE ? male : female; }
        public String encode() { return male.equals(female) ? male : male + "|" + female; }
        public static Family decode(String value) {
            int bar = value.indexOf('|');
            return bar < 0 ? new Family(value, value) : new Family(value.substring(0, bar), value.substring(bar + 1));
        }
    }

    public NamePool {
        if (culture == null || !culture.matches("[a-z]{2,8}")) throw new IllegalArgumentException("Invalid name culture");
        male = List.copyOf(male); female = List.copyOf(female); families = List.copyOf(families);
        if (male.size() < 4 || female.size() < 4 || families.size() < 4 || male.size() > 1024 || female.size() > 1024 || families.size() > 1024)
            throw new IllegalArgumentException("Name pool " + culture + " needs 4..1024 names per list");
        male.forEach(NamePool::check); female.forEach(NamePool::check);
    }

    public String given(Sex sex, int roll) {
        List<String> list = sex == Sex.MALE ? male : female;
        return list.get(Math.floorMod(roll, list.size()));
    }
    public Family family(int roll) { return families.get(Math.floorMod(roll, families.size())); }

    static void check(String name) {
        if (name == null || name.isBlank() || name.length() > 24 || name.contains("|") || name.contains("@") || name.contains("#"))
            throw new IllegalArgumentException("Invalid name '" + name + "'");
    }

    public static NamePool decode(String culture, Obj json) {
        json.allow("schemaVersion", "male", "female", "family");
        if (json.intValue("schemaVersion") != 1) throw json.fail("unsupported schemaVersion");
        List<Family> families = new ArrayList<>();
        for (Object value : json.list("family", 1024)) {
            if (value instanceof String s) families.add(new Family(s, s));
            else if (value instanceof List<?> pair && pair.size() == 2 && pair.get(0) instanceof String m && pair.get(1) instanceof String f) families.add(new Family(m, f));
            else throw json.fail("family entries are \"Name\" or [\"male\",\"female\"]");
        }
        return new NamePool(culture, json.strings("male", 1024), json.strings("female", 1024), families);
    }
}
