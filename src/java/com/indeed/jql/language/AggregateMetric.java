package com.indeed.jql.language;

import com.google.common.base.Function;
import com.google.common.base.Optional;

import java.util.List;

public interface AggregateMetric {

    AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i);

    abstract class Unop implements AggregateMetric {
        public final AggregateMetric m1;

        public Unop(AggregateMetric m1) {
            this.m1 = m1;
        }
    }

    class Log extends Unop {
        public Log(AggregateMetric m1) {
            super(m1);
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Log(m1.traverse(f, g, h, i)));
        }
    }

    class Negate extends Unop {
        public Negate(AggregateMetric m1) {
            super(m1);
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Negate(m1.traverse(f, g, h, i)));
        }
    }

    class Abs extends Unop {
        public Abs(AggregateMetric m1) {
            super(m1);
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Abs(m1.traverse(f, g, h, i)));
        }
    }

    abstract class Binop implements AggregateMetric {
        public final AggregateMetric m1;
        public final AggregateMetric m2;

        public Binop(AggregateMetric m1, AggregateMetric m2) {
            this.m1 = m1;
            this.m2 = m2;
        }
    }

    class Add extends Binop {
        public Add(AggregateMetric m1, AggregateMetric m2) {
            super(m1, m2);
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Add(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Subtract extends Binop {
        public Subtract(AggregateMetric m1, AggregateMetric m2) {
            super(m1, m2);
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Subtract(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Multiply extends Binop {
        public Multiply(AggregateMetric m1, AggregateMetric m2) {
            super(m1, m2);
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Multiply(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Divide extends Binop {
        public Divide(AggregateMetric m1, AggregateMetric m2) {
            super(m1, m2);
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Divide(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Modulus extends Binop {
        public Modulus(AggregateMetric m1, AggregateMetric m2) {
            super(m1, m2);
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Modulus(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Power extends Binop {
        public Power(AggregateMetric m1, AggregateMetric m2) {
            super(m1, m2);
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Power(m1.traverse(f, g, h, i), m2.traverse(f, g, h, i)));
        }
    }

    class Parent implements AggregateMetric {
        public final AggregateMetric metric;

        public Parent(AggregateMetric metric) {
            this.metric = metric;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Parent(metric.traverse(f, g, h, i)));
        }
    }

    class Lag implements AggregateMetric {
        public final int lag;
        public final AggregateMetric metric;

        public Lag(int lag, AggregateMetric metric) {
            this.lag = lag;
            this.metric = metric;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Lag(lag, metric.traverse(f, g, h, i)));
        }
    }

    class Window implements AggregateMetric {
        public final int window;
        public final AggregateMetric metric;

        public Window(int window, AggregateMetric metric) {
            this.window = window;
            this.metric = metric;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Window(window, metric.traverse(f, g, h, i)));
        }
    }

    class Qualified implements AggregateMetric {
        public final List<String> scope;
        public final AggregateMetric metric;

        public Qualified(List<String> scope, AggregateMetric metric) {
            this.scope = scope;
            this.metric = metric;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Qualified(scope, metric.traverse(f, g, h, i)));
        }
    }

    class DocStats implements AggregateMetric {
        public final DocMetric metric;

        public DocStats(DocMetric metric) {
            this.metric = metric;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new DocStats(metric.traverse(g, i)));
        }
    }

    /**
     * DocStats in which there is no explicit sum, but a single atomic token. Could be a boolean on DocStats but whatever.
     */
    class ImplicitDocStats implements AggregateMetric {
        public final String field;

        public ImplicitDocStats(String field) {
            this.field = field;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(this);
        }
    }

    class Constant implements AggregateMetric {
        public final double value;

        public Constant(double value) {
            this.value = value;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(this);
        }
    }

    class Percentile implements AggregateMetric {
        public final String field;
        public final double percentile;

        public Percentile(String field, double percentile) {
            this.field = field;
            this.percentile = percentile;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(this);
        }
    }

    class Running implements AggregateMetric {
        public final AggregateMetric metric;

        public Running(AggregateMetric metric) {
            this.metric = metric;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Running(metric.traverse(f, g, h, i)));
        }
    }

    class Distinct implements AggregateMetric {
        public final String field;
        public final Optional<AggregateFilter> filter;
        public final Optional<Integer> windowSize;

        public Distinct(String field, Optional<AggregateFilter> filter, Optional<Integer> windowSize) {
            this.field = field;
            this.filter = filter;
            this.windowSize = windowSize;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            if (filter.isPresent()) {
                return f.apply(new Distinct(field, Optional.of(filter.get().traverse(f, g, h, i)), windowSize));
            } else {
                return f.apply(this);
            }
        }
    }

    class Named implements AggregateMetric {
        public final AggregateMetric metric;
        public final String name;

        public Named(AggregateMetric metric, String name) {
            this.metric = metric;
            this.name = name;
        }

        @Override
        public AggregateMetric traverse(Function<AggregateMetric, AggregateMetric> f, Function<DocMetric, DocMetric> g, Function<AggregateFilter, AggregateFilter> h, Function<DocFilter, DocFilter> i) {
            return f.apply(new Named(metric.traverse(f, g, h, i), name));
        }
    }
}
