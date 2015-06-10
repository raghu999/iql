grammar JQL;

LAG : 'LAG' ;
RUNNING : 'RUNNING' ;
PARENT : 'PARENT' ;
DISTINCT : 'DISTINCT' ;
DISTINCT_WINDOW : 'DISTINCT_WINDOW' ;
WINDOW : 'WINDOW' ;
PERCENTILE : 'PERCENTILE' ;
PDIFF : 'PDIFF' ;
AVG : 'AVG' ;
VARIANCE : 'VARIANCE' ;
STDEV : 'STDEV' ;
LOG : 'LOG' ;
ABS : 'ABS' ;
SUM_OVER : 'SUM_OVER' ;
AVG_OVER : 'AVG_OVER' ;
WHERE : 'WHERE' ;
HASSTR : 'HASSTR' ;
HASINT : 'HASINT' ;
SELECT : 'SELECT' ;
FROM : 'FROM' ;
GROUP : 'GROUP' ;
BY : 'BY' ;
AGO : 'AGO' ;
COUNT : 'COUNT' ;
AS : 'AS' ;
NOT : 'NOT' ;
LUCENE : 'LUCENE' ;
QUERY : 'QUERY' ;
TOP : 'TOP' ;
BOTTOM : 'BOTTOM' ;
WITH : 'WITH' ;
DEFAULT : 'DEFAULT' ;
TIME : 'TIME' ;
TIMEBUCKETS : 'TIMEBUCKETS' ;
TO : 'TO' ;
BUCKETS : 'BUCKETS' ;
BUCKET : 'BUCKET' ;
IN : 'IN' ;
DESCENDING : 'DESCENDING' ;
DESC : 'DESC' ;
ASCENDING : 'ASCENDING' ;
ASC : 'ASC' ;
DAYOFWEEK : 'DAYOFWEEK' ;
QUANTILES : 'QUANTILES' ;
BETWEEN : 'BETWEEN' ;
SAMPLE : 'SAMPLE' ;
AND : 'AND' ;
OR : 'OR' ;
TRUE : 'TRUE' ;
FALSE : 'FALSE' ;
IF : 'IF' ;
THEN : 'THEN' ;
ELSE : 'ELSE' ;
FLOATSCALE : 'FLOATSCALE' ;
SIGNUM : 'SIGNUM' ;
LIMIT : 'LIMIT';

TIME_UNIT : [SMHDWYB]|'SECOND'|'SECONDS'|'MINUTE'|'MINUTES'|'HOUR'|'HOURS'|'DAY'|'DAYS'|'WEEK'|'WEEKS'|'MONTH'|'MONTHS'|'YEAR'|'YEARS';

INT : [0-9]+ ;
DOUBLE: [0-9]+ ('.' [0-9]*)? ;

fragment DIGIT : [0-9] ;
DATETIME_TOKEN
 : DIGIT DIGIT DIGIT DIGIT
    ('-' DIGIT DIGIT
        ('-' DIGIT DIGIT
            (('T'|' ') DIGIT DIGIT
                (':' DIGIT DIGIT
                    (':' DIGIT DIGIT
                        ('.' DIGIT DIGIT DIGIT
                            ('+'|'-' DIGIT DIGIT ':' DIGIT DIGIT)?
                        )?
                    )?
                )
            )?
        )?
    ) ;
DATE_TOKEN : DIGIT DIGIT DIGIT DIGIT ('-' DIGIT DIGIT ('-' DIGIT DIGIT)?)? ;

ID : [a-zA-Z_][a-zA-Z0-9_]* ;

identifier
    : TIME_UNIT | ID | LAG | RUNNING | PARENT | DISTINCT | DISTINCT_WINDOW | WINDOW | PERCENTILE | PDIFF | AVG
    | VARIANCE | STDEV | LOG | ABS | SUM_OVER | AVG_OVER | WHERE | HASSTR | HASINT | SELECT | FROM | GROUP | BY
    | AGO | COUNT | AS | NOT | LUCENE | QUERY | TOP | BOTTOM | WITH | DEFAULT | TIME | TIMEBUCKETS | TO
    | BUCKETS | BUCKET | IN | DESCENDING | DESC | ASCENDING | ASC | DAYOFWEEK | QUANTILES | BETWEEN
    | SAMPLE | AND | OR | TRUE | FALSE | IF | THEN | ELSE | FLOATSCALE | SIGNUM | LIMIT
    ;
timePeriod : (coeffs+=INT units+=(TIME_UNIT | BUCKET | BUCKETS))+ AGO?;

WS : [ \t\r\n]+ -> skip ;
COMMENT : '/*' .*? '*/' -> skip ;

number : INT | DOUBLE ;


fragment ESCAPED_SINGLE_QUOTE : '\\\'';
fragment SINGLE_QUOTED_CONTENTS : ( ESCAPED_SINGLE_QUOTE | ~('\n'|'\r') )*? ;
fragment SINGLE_QUOTED_STRING : '\'' SINGLE_QUOTED_CONTENTS '\'';

fragment ESCAPED_DOUBLE_QUOTE : '\\"';
fragment DOUBLE_QUOTED_CONTENTS : ( ESCAPED_DOUBLE_QUOTE | ~('\n'|'\r') )*? ;
fragment DOUBLE_QUOTED_STRING : '"' DOUBLE_QUOTED_CONTENTS '"';

STRING_LITERAL : SINGLE_QUOTED_STRING | DOUBLE_QUOTED_STRING ;

aggregateMetric
    : (COUNT '(' ')') # AggregateCounts
    | LAG '(' INT ',' aggregateMetric ')' # AggregateLag
    | RUNNING '(' aggregateMetric ')' # AggregateRunning
    | PARENT '(' aggregateMetric ')' # AggregateParent
    | DISTINCT '(' identifier (WHERE aggregateFilter)? ')' # AggregateDistinct
    | DISTINCT_WINDOW '(' INT ',' identifier (WHERE aggregateFilter) ')' # AggregateDistinctWindow
    | WINDOW '(' INT ',' aggregateMetric ')' # AggregateWindow
    | PERCENTILE '(' identifier ',' number ')' # AggregatePercentile
    | PDIFF '(' expected=aggregateMetric ',' actual=aggregateMetric ')' # AggregatePDiff
    | AVG '(' aggregateMetric ')' # AggregateAvg
    | VARIANCE '(' docMetric ')' # AggregateVariance
    | STDEV '(' docMetric ')' # AggregateStandardDeviation
    | LOG '(' aggregateMetric ')' # AggregateLog
    | ABS '(' aggregateMetric ')' # AggregateAbs
    | SUM_OVER '(' groupByElement ',' aggregateMetric ')' # AggregateSumAcross
    | AVG_OVER '(' field=identifier (WHERE aggregateFilter)? ',' aggregateMetric ')' # AggregateAverageAcross
    | scope ':' '(' aggregateMetric ')' # AggregateQualified
    | identifier # AggregateRawField
    | '[' docMetric ']' # AggregateSum
    | '-' aggregateMetric # AggregateNegate
    | <assoc=right> aggregateMetric '^' aggregateMetric # AggregatePower
    | aggregateMetric '*' aggregateMetric # AggregateMult
    | aggregateMetric '/' aggregateMetric # AggregateDiv
    | aggregateMetric '%' aggregateMetric # AggregateMod
    | aggregateMetric '+' aggregateMetric # AggregatePlus
    | aggregateMetric '-' aggregateMetric # AggregateMinus
    | '(' aggregateMetric ')' # AggregateParens
    | number # AggregateConstant
    | aggregateMetric AS name=identifier # AggregateNamed
    ;

scope : '[' datasets+=identifier (',' datasets+=identifier)* ']' ;

aggregateFilter
    : field=identifier '=~' STRING_LITERAL # AggregateRegex
    | field=identifier '!=~' STRING_LITERAL # AggregateNotRegex
    | 'TERM()' '=' termVal # AggregateTermIs
    | aggregateMetric op=('='|'!='|'<'|'<='|'>'|'>=') aggregateMetric # AggregateMetricInequality
    | (NOT|'-'|'!') aggregateFilter # AggregateNot
    | aggregateFilter (AND | '&&') aggregateFilter # AggregateAnd
    | aggregateFilter (OR | '||') aggregateFilter # AggregateOr
    | '(' aggregateFilter ')' # AggregateFilterParens
    | TRUE # AggregateTrue
    | FALSE # AggregateFalse
    ;

docMetric
    : COUNT '(' ')' # DocCounts
    | ABS '(' docMetric ')' # DocAbs
    | SIGNUM '(' docMetric ')' # DocSignum
    /* TODO: identifier */
    | field=identifier ('='|':') term=(STRING_LITERAL | ID | TIME_UNIT | INT) # DocHasString
    /* TODO: identifier */
    | HASSTR '(' field=identifier ',' term=(STRING_LITERAL | ID | TIME_UNIT | INT) ')' # DocHasString
    | HASSTR '(' STRING_LITERAL ')' # DocHasStringQuoted
    /* TODO: identifier */
    | field=identifier '!=' term=(STRING_LITERAL | ID | TIME_UNIT) # DocHasntString
    | field=identifier ('='|':') term=INT # DocHasInt
    | HASINT '(' field=identifier ',' term=INT ')' # DocHasInt
    | HASINT '(' STRING_LITERAL ')' # DocHasIntQuoted
    | field=identifier '!=' INT # DocHasntInt
    | FLOATSCALE '(' field=identifier ',' mult=INT ',' add=INT ')' # DocFloatScale
    | IF filter=docFilter THEN trueCase=docMetric ELSE falseCase=docMetric # DocIfThenElse
    | '-' docMetric # DocNegate
    | docMetric '*' docMetric # DocMult
    | docMetric '/' docMetric # DocDiv
    | docMetric '%' docMetric # DocMod
    | docMetric '+' docMetric # DocPlus
    | docMetric '-' docMetric # DocMinus
    | '(' docMetric ')' # DocMetricParens
    | identifier # DocRawField
    | INT # DocInt
    ;

termVal
    : INT # IntTerm
    | STRING_LITERAL # StringTerm
    | identifier # StringTerm
    ;

docFilter
    : field=identifier '=~' STRING_LITERAL # DocRegex
    | field=identifier '!=~' STRING_LITERAL # DocNotRegex
    | field=identifier ('='|':') termVal # DocFieldIs
    | field=identifier '!=' termVal # DocFieldIsnt
    | field=identifier not=NOT? IN '(' (terms += termVal)? (',' terms += termVal)* ')' # DocFieldIn
    | docMetric op=('='|'!='|'<'|'<='|'>'|'>=') docMetric # DocMetricInequality
    | (LUCENE | QUERY) '(' STRING_LITERAL ')' # Lucene
    | BETWEEN '(' field=identifier ',' lowerBound=INT ',' upperBound=INT ')' # DocBetween
    | SAMPLE '(' field=identifier ',' numerator=INT (',' denominator=INT (',' seed=(STRING_LITERAL | INT))?)? ')' # DocSample
    | ('-'|'!'|NOT) docFilter # DocNot
    | docFilter (AND|'&&') docFilter # DocAnd
    | docFilter (OR|'||') docFilter # DocOr
    | '(' docFilter ')' # DocFilterParens
    | TRUE # DocTrue
    | FALSE # DocFalse
    ;

groupByElement
    : DAYOFWEEK # DayOfWeekGroupBy
    | QUANTILES '(' field=identifier ',' INT ')' # QuantilesGroupBy
    | topTermsGroupByElem # TopTermsGroupBy
    | field=identifier not=NOT? IN '(' (terms += termVal)? (',' terms += termVal)* ')' (withDefault=WITH DEFAULT)? # GroupByFieldIn
    | groupByMetric # MetricGroupBy
    | groupByMetricEnglish # MetricGroupBy
    | groupByTime # TimeGroupBy
    | groupByField # FieldGroupBy
    ;

topTermsGroupByElem
    : 'TOPTERMS'
        '('
            field=identifier
            (',' limit=INT
                (',' metric=aggregateMetric
                    (',' order=(BOTTOM | DESCENDING | DESC | TOP | ASCENDING | ASC))?
                )?
            )?
        ')'
    ;

groupByMetric
    : (BUCKETS | BUCKET) '(' docMetric ',' min=INT ',' max=INT ',' interval=INT ')'
    ;

groupByMetricEnglish
    : docMetric FROM min=INT TO max=INT BY interval=INT
    ;

groupByTime
    : (TIME | TIMEBUCKETS) ('(' timePeriod (',' timeField=identifier)? ')')?
    ;

groupByField
    : field=identifier ('[' order=(TOP | BOTTOM)? limit=INT? (BY metric=aggregateMetric)? (WHERE filter=aggregateFilter)? ']')? (withDefault=WITH DEFAULT)?
    ;

dateTime
    : DATETIME_TOKEN
    | DATE_TOKEN
    | STRING_LITERAL
    | timePeriod
    // Oh god I hate myself:
    | 'TODAY'
    | 'TODA'
    | 'TOD'
    | 'TOMORROW'
    | 'TOMORRO'
    | 'TOMORR'
    | 'TOMOR'
    | 'TOMO'
    | 'TOM'
    | 'YESTERDAY'
    | 'YESTERDA'
    | 'YESTERD'
    | 'YESTER'
    | 'YESTE'
    | 'YEST'
    | 'YES'
    | 'YE'
    | 'Y'
    ;

dataset
    : index=identifier start=dateTime end=dateTime (AS name=identifier)?
    ;

datasetOptTime
    : dataset # FullDataset
    | index=identifier (AS name=identifier)? # PartialDataset
    ;

fromContents
    : dataset (',' datasetOptTime)*
    ;

groupByContents
    : (groupByElement (',' groupByElement)*)?
    ;

selectContents
    : (aggregateMetric (',' aggregateMetric)*)?
    ;

query
    : (SELECT selects+=selectContents)?
      FROM fromContents
      (WHERE docFilter+)?
      (GROUP BY groupByContents)?
      (SELECT selects+=selectContents)?
      (LIMIT limit=INT)?
    ;