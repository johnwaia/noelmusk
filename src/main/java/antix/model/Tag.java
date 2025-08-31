package antix.model;

import java.util.Objects;

public class Tag {
    private String name;

    public Tag() {}
    public Tag(String name) { this.name = name; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tag)) return false;
        Tag tag = (Tag) o;
        return Objects.equals(name, tag.name);
    }
    @Override public int hashCode() { return Objects.hash(name); }
    @Override public String toString() { return name; }
}
