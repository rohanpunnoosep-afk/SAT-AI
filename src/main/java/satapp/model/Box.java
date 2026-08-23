package satapp.model;

public class Box {

    private String id;
    private String label;
    private long createdAt;

    public Box() {
    }

    public Box(String id, String label, long createdAt) {
        this.id = id;
        this.label = label;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
