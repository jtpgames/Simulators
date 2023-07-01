# Simulators
This repository contains proof-of-concept implementations of the RAST (introduced at [MASCOTS2022](https://doi.org/10.1109/MASCOTS56607.2022.00015))
Simulator component for the following Systems Under Evaluation:

1. Alarm Receiving Software (ARS): ARS is a commercial software system from the GS company group. For more information, visit [GS Electronic](https://www.gselectronic.com).
2. TeaStore: TeaStore is an open-source project hosted on GitHub. You can find the repository at [TeaStore Repository](https://github.com/DescartesResearch/TeaStore).

# How to Start the Simulator
To start the Simulator, follow these steps:

1. Navigate to your local Simulator folder.
2. Execute the command `./gradlew run`.
3. The specific Simulator to start is defined in the `build.gradle.kts` file on the line `mainClass.set("TeastoreKt")`.
4. If the Simulator starts successfully, you will see the following line printed on the console: `INFO ktor.application - Responding at http://0.0.0.0:8081`.
5. To terminate the Simulator, press `Ctrl + C`.

# How to Adjust the Predictive Model
To adjust the predictive model for either the ARS or the TeaStore Simulator, follow these steps:

1. For ARS Simulator:
    - Navigate to the `ars.kt` file.
    - Locate the `MODEL_TO_USE` variable and change its default value.
    - The available models and mappings can be found in the following folder: `<your_local_simulators_folder>/src/main/resources`.
      Look for files starting with the word "gs".

2. For TeaStore Simulator:
    - Navigate to the `teastore.kt` file.
    - Locate the `modelToUse` variable and change its default value.
    - The available models and mappings can be found in the following folder: `<your_local_simulators_folder>/src/main/resources`.
      Look for files starting with the word "teastore".
