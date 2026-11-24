package mysql

import (
	"strings"
	"testing"
)

func TestSplitStatementsKeepsQuotedSemicolonsAndComments(t *testing.T) {
	body := `
-- leading comment with ; inside
CREATE TABLE a (
  note VARCHAR(20) DEFAULT 'x;y',
  ` + "`odd;name`" + ` INT
);
/* block ; comment */
CREATE TABLE b (id INT); -- trailing ; comment
`
	statements := splitStatements(body)
	if len(statements) != 2 {
		t.Fatalf("statements = %d, want 2: %#v", len(statements), statements)
	}
	if !strings.Contains(statements[0], "'x;y'") ||
		!strings.Contains(statements[0], "`odd;name`") {
		t.Fatalf("quoted semicolon was split: %q", statements[0])
	}
	if !strings.HasPrefix(statements[1], "/* block ; comment */") ||
		!strings.HasSuffix(statements[1], "CREATE TABLE b (id INT)") {
		t.Fatalf("unexpected second statement: %q", statements[1])
	}
}

func TestSplitStatementsSkipsCommentOnlyInput(t *testing.T) {
	if statements := splitStatements("-- only a comment ;\n/* and a block ; */\n"); len(statements) != 0 {
		t.Fatalf("statements = %#v, want none", statements)
	}
}

func TestSplitStatementsHandlesEscapedQuote(t *testing.T) {
	statements := splitStatements("INSERT INTO a VALUES ('it''s; fine');")
	if len(statements) != 1 || !strings.Contains(statements[0], "it''s; fine") {
		t.Fatalf("unexpected statements: %#v", statements)
	}
}
