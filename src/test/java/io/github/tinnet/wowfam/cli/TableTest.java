package io.github.tinnet.wowfam.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

final class TableTest {

  @Test
  void columnsFitTheirWidestCell() {
    var table =
        new Table("ADDON", "INSTALLED", "SOURCE")
            .row("questie", "12.6.5+v1.0.13", "github:Questie/Questie")
            .row("spoken", "3.8.6", "github:rusty-key/spoken-wow");

    assertThat(print(table))
        .isEqualTo(
            """
            ADDON    INSTALLED       SOURCE
            questie  12.6.5+v1.0.13  github:Questie/Questie
            spoken   3.8.6           github:rusty-key/spoken-wow
            """);
  }

  @Test
  void rejectsRowsOfTheWrongLength() {
    assertThatThrownBy(() -> new Table("A", "B").row("only one"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String print(Table table) {
    var out = new StringWriter();
    try (var writer = new PrintWriter(out)) {
      table.print(writer);
    }
    return out.toString().replace(System.lineSeparator(), "\n");
  }
}
