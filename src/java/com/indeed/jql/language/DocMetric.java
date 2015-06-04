package com.indeed.jql.language;

import com.google.common.base.Function;

public interface DocMetric {

    DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i);

    class Field implements DocMetric {
        private final String field;

        public Field(String field) {
            this.field = field;
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(this);
        }
    }

    abstract class Unop implements DocMetric {
        protected final DocMetric m1;

        public Unop(DocMetric m1) {
            this.m1 = m1;
        }
    }

    class Log extends Unop {
        public Log(DocMetric m1) {
            super(m1);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Log(m1.traverse(f,g,h,i)));
        }
    }

    class Negate extends Unop {
        public Negate(DocMetric m1) {
            super(m1);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Negate(m1.traverse(f, g, h, i)));
        }
    }

    class Abs extends Unop {
        public Abs(DocMetric m1) {
            super(m1);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Abs(m1.traverse(f, g, h, i)));
        }
    }

    class Signum extends Unop {
        public Signum(DocMetric m1) {
            super(m1);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Signum(m1.traverse(f, g, h, i)));
        }
    }

    abstract class Binop implements DocMetric {
        protected final DocMetric m1;
        protected final DocMetric m2;

        public Binop(DocMetric m1, DocMetric m2) {
            this.m1 = m1;
            this.m2 = m2;
        }
    }

    class Add extends Binop {
        public Add(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Add(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Subtract extends Binop {
        public Subtract(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Subtract(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Multiply extends Binop {
        public Multiply(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Multiply(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Divide extends Binop {
        public Divide(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Divide(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Modulus extends Binop {
        public Modulus(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Modulus(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Min extends Binop {
        public Min(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Min(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Max extends Binop {
        public Max(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new Max(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class MetricEqual extends Binop {
        public MetricEqual(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new MetricEqual(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class MetricNotEqual extends Binop {
        public MetricNotEqual(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new MetricNotEqual(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class MetricLt extends Binop {
        public MetricLt(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new MetricLt(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class MetricLte extends Binop {
        public MetricLte(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new MetricLte(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class MetricGt extends Binop {
        public MetricGt(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new MetricGt(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class MetricGte extends Binop {
        public MetricGte(DocMetric m1, DocMetric m2) {
            super(m1, m2);
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new MetricGte(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class RegexMetric implements DocMetric {
        private final String field;
        private final String regex;

        public RegexMetric(String field, String regex) {
            this.field = field;
            this.regex = regex;
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(this);
        }
    }

    class FloatScale implements DocMetric {
        private final String field;
        private final double mult;
        private final double add;

        public FloatScale(String field, double mult, double add) {
            this.field = field;
            this.mult = mult;
            this.add = add;
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(this);
        }
    }

    class Constant implements DocMetric {
        private final long value;

        public Constant(long value) {
            this.value = value;
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(this);
        }
    }

    class HasInt implements DocMetric {
        private final String field;
        private final long term;

        public HasInt(String field, long term) {
            this.field = field;
            this.term = term;
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(this);
        }
    }

    class HasString implements DocMetric {
        private final String field;
        private final String term;

        public HasString(String field, String term) {
            this.field = field;
            this.term = term;
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(this);
        }
    }

    class IfThenElse implements DocMetric {
        private final DocFilter condition;
        private final DocMetric trueCase;
        private final DocMetric falseCase;

        public IfThenElse(DocFilter condition, DocMetric trueCase, DocMetric falseCase) {
            this.condition = condition;
            this.trueCase = trueCase;
            this.falseCase = falseCase;
        }

        @Override
        public DocMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return g.apply(new IfThenElse(condition.traverse(f, g, h, i), trueCase.traverse(f, g, h, i), falseCase.traverse(f, g, h, i)));
        }
    }

}
