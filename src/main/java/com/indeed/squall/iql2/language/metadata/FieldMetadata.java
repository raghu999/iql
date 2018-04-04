package com.indeed.squall.iql2.language.metadata;

import org.immutables.value.Value;

import javax.annotation.Nullable;
import java.util.Comparator;

/**
 * @author yuanlei
 */

@Value.Style(allParameters = true, init = "set*")
@Value.Immutable
public abstract class FieldMetadata {
    public abstract String getName();
    @Nullable public abstract String getDescription();
    public abstract Type getType();

    public enum Type {
        String,
        Integer
    }

    public static final Comparator<FieldMetadata> CASE_INSENSITIVE_ORDER = new CaseInsensitiveComparator();

    public static final class CaseInsensitiveComparator implements Comparator<FieldMetadata> {
        public int compare(FieldMetadata f1, FieldMetadata f2) {
            String s1 = f1.getName();
            String s2 = f2.getName();
            return s1.compareToIgnoreCase(s2);
        }
    }
}
